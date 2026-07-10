import type {InterviewVideoChunk} from '../api/interviewVideos';
import {
  interviewVideoApi,
  VIDEO_CHUNK_CONFLICT_CODE,
} from '../api/interviewVideos';
import {ApiError} from '../api/request';
import type {VisionEventType} from '../api/interviewVision';
import {interviewVisionApi} from '../api/interviewVision';
import {getAccessToken} from './authStorage';

const DATABASE_NAME = 'interview-guide-uploads';
const DATABASE_VERSION = 1;
const STORE_NAME = 'pendingUploads';
const MAX_RECORD_AGE_MS = 7 * 24 * 60 * 60 * 1000;

interface PendingUploadBase {
  id: string;
  sessionId: string;
  createdAt: number;
  attempts: number;
}

interface PendingVideoUpload extends PendingUploadBase {
  kind: 'video';
  chunkIndex: number;
  durationMs: number;
  blob: Blob;
}

interface PendingProctorUpload extends PendingUploadBase {
  kind: 'proctor';
  clientEventId: string;
  eventType: VisionEventType;
  evidence?: Blob;
  metadata?: Record<string, unknown>;
  videoOffsetMs?: number;
}

type PendingUpload = PendingVideoUpload | PendingProctorUpload;

let databasePromise: Promise<IDBDatabase> | null = null;
let flushPromise: Promise<void> | null = null;

function openDatabase(): Promise<IDBDatabase> {
  if (databasePromise) return databasePromise;
  databasePromise = new Promise((resolve, reject) => {
    const request = indexedDB.open(DATABASE_NAME, DATABASE_VERSION);
    request.onupgradeneeded = () => {
      const database = request.result;
      if (!database.objectStoreNames.contains(STORE_NAME)) {
        const store = database.createObjectStore(STORE_NAME, {keyPath: 'id'});
        store.createIndex('sessionId', 'sessionId');
        store.createIndex('createdAt', 'createdAt');
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
  return databasePromise;
}

async function runTransaction<T>(
  mode: IDBTransactionMode,
  action: (store: IDBObjectStore, resolve: (value: T) => void) => void,
): Promise<T> {
  const database = await openDatabase();
  return new Promise<T>((resolve, reject) => {
    const transaction = database.transaction(STORE_NAME, mode);
    transaction.onerror = () => reject(transaction.error);
    action(transaction.objectStore(STORE_NAME), resolve);
  });
}

async function save(record: PendingUpload): Promise<void> {
  await runTransaction<void>('readwrite', (store, resolve) => {
    const request = store.put(record);
    request.onsuccess = () => resolve();
  });
}

async function remove(id: string): Promise<void> {
  await runTransaction<void>('readwrite', (store, resolve) => {
    const request = store.delete(id);
    request.onsuccess = () => resolve();
  });
}

async function list(): Promise<PendingUpload[]> {
  return runTransaction<PendingUpload[]>('readonly', (store, resolve) => {
    const request = store.getAll();
    request.onsuccess = () => resolve(request.result as PendingUpload[]);
  });
}

function isVideoChunkConflict(error: unknown): boolean {
  return error instanceof ApiError && error.code === VIDEO_CHUNK_CONFLICT_CODE;
}

async function upload(record: PendingUpload): Promise<InterviewVideoChunk | void> {
  if (record.kind === 'video') {
    return interviewVideoApi.uploadChunk(
      record.sessionId,
      record.chunkIndex,
      record.durationMs,
      record.blob,
    );
  }
  await interviewVisionApi.recordProctorEvent(
    record.sessionId,
    record.clientEventId,
    record.eventType,
    record.evidence,
    record.metadata,
    record.videoOffsetMs,
  );
}

function newClientEventId(): string {
  return crypto.randomUUID();
}

export async function uploadVideoChunkReliably(
  sessionId: string,
  chunkIndex: number,
  durationMs: number,
  blob: Blob,
): Promise<InterviewVideoChunk> {
  const record: PendingVideoUpload = {
    id: `video:${sessionId}:${chunkIndex}`,
    kind: 'video',
    sessionId,
    chunkIndex,
    durationMs,
    blob,
    createdAt: Date.now(),
    attempts: 0,
  };
  try {
    await save(record);
  } catch {
    return interviewVideoApi.uploadChunk(sessionId, chunkIndex, durationMs, blob);
  }
  try {
    const uploaded = await upload(record) as InterviewVideoChunk;
    await remove(record.id);
    return uploaded;
  } catch (error) {
    if (isVideoChunkConflict(error)) {
      await remove(record.id);
    }
    throw error;
  }
}

export async function uploadProctorEventReliably(
  sessionId: string,
  eventType: VisionEventType,
  evidence?: Blob,
  metadata?: Record<string, unknown>,
  videoOffsetMs?: number,
): Promise<void> {
  const clientEventId = newClientEventId();
  const record: PendingProctorUpload = {
    id: `proctor:${sessionId}:${clientEventId}`,
    kind: 'proctor',
    sessionId,
    clientEventId,
    eventType,
    evidence,
    metadata,
    videoOffsetMs,
    createdAt: Date.now(),
    attempts: 0,
  };
  try {
    await save(record);
  } catch {
    return interviewVisionApi.recordProctorEvent(
      sessionId, clientEventId, eventType, evidence, metadata, videoOffsetMs);
  }
  await upload(record);
  await remove(record.id);
}

export async function flushPendingInterviewUploads(): Promise<void> {
  if (!getAccessToken() || !navigator.onLine) return;
  if (flushPromise) return flushPromise;
  flushPromise = (async () => {
    const records = (await list()).sort((left, right) => left.createdAt - right.createdAt);
    for (const record of records) {
      if (Date.now() - record.createdAt > MAX_RECORD_AGE_MS) {
        await remove(record.id);
        continue;
      }
      try {
        await upload(record);
        await remove(record.id);
      } catch (error) {
        if (record.kind === 'video' && isVideoChunkConflict(error)) {
          await remove(record.id);
          continue;
        }
        await save({...record, attempts: record.attempts + 1});
        if (!navigator.onLine) break;
      }
    }
  })().finally(() => {
    flushPromise = null;
  });
  return flushPromise;
}

export async function resolveNextVideoChunkIndex(sessionId: string): Promise<number> {
  const serverChunks = await interviewVideoApi.list(sessionId);
  let pendingUploads: PendingUpload[] = [];
  try {
    pendingUploads = await list();
  } catch {
    // IndexedDB 不可用时，服务端已有分片仍可保证刷新后的编号连续。
  }
  const indexes = [
    ...serverChunks.map(chunk => chunk.chunkIndex),
    ...pendingUploads
      .filter((record): record is PendingVideoUpload => (
        record.kind === 'video' && record.sessionId === sessionId
      ))
      .map(record => record.chunkIndex),
  ];
  return indexes.length === 0 ? 0 : Math.max(...indexes) + 1;
}

export async function countPendingInterviewUploads(sessionId: string): Promise<number> {
  try {
    return (await list()).filter(record => record.sessionId === sessionId).length;
  } catch {
    return 0;
  }
}

export function initializeInterviewUploadRecovery(): () => void {
  const flush = () => void flushPendingInterviewUploads();
  const handleVisibility = () => {
    if (document.visibilityState === 'visible') flush();
  };
  window.addEventListener('online', flush);
  document.addEventListener('visibilitychange', handleVisibility);
  const timer = window.setInterval(flush, 30_000);
  flush();
  return () => {
    window.removeEventListener('online', flush);
    document.removeEventListener('visibilitychange', handleVisibility);
    window.clearInterval(timer);
  };
}
