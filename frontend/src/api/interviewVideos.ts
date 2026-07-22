import {ApiError, request} from './request';

export const VIDEO_CHUNK_CONFLICT_CODE = 3011;

export interface InterviewVideoChunk {
  id: number;
  sessionId: string;
  mimeType: string;
  fileSize: number;
  durationMs: number;
  chunkIndex: number;
  checksum: string;
  status: 'UPLOADING' | 'UPLOADED' | 'FAILED' | 'MERGED';
  createdAt: string;
}

export interface VideoUploadCompleteResponse {
  sessionId: string;
  chunkCount: number;
  totalSize: number;
  totalDurationMs: number;
  complete: boolean;
}

export interface ObjectAccessResponse {
  url: string;
  direct: boolean;
  mimeType: string;
  expiresAt: string;
}

export interface PlayableObject {
  url: string;
  revoke: () => void;
  durationMs?: number;
}

async function resolvePlayableObject(access: ObjectAccessResponse): Promise<PlayableObject> {
  if (access.direct) return {url: access.url, revoke: () => undefined};
  const response = await request.getInstance().get<Blob>(access.url, {responseType: 'blob'});
  const url = URL.createObjectURL(response.data);
  return {url, revoke: () => URL.revokeObjectURL(url)};
}

async function sha256(blob: Blob): Promise<string> {
  const bytes = await blob.arrayBuffer();
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  return Array.from(new Uint8Array(digest))
    .map(value => value.toString(16).padStart(2, '0'))
    .join('');
}

async function retry<T>(action: () => Promise<T>, attempts = 3): Promise<T> {
  let lastError: unknown;
  for (let attempt = 0; attempt < attempts; attempt++) {
    try {
      return await action();
    } catch (error) {
      lastError = error;
      if (error instanceof ApiError && error.code === VIDEO_CHUNK_CONFLICT_CODE) {
        break;
      }
      if (attempt < attempts - 1) {
        await new Promise(resolve => window.setTimeout(resolve, 1000 * 2 ** attempt));
      }
    }
  }
  throw lastError;
}

export const interviewVideoApi = {
  async uploadChunk(
    sessionId: string,
    chunkIndex: number,
    durationMs: number,
    blob: Blob,
  ): Promise<InterviewVideoChunk> {
    const checksum = await sha256(blob);
    const formData = new FormData();
    formData.append('chunkIndex', String(chunkIndex));
    formData.append('durationMs', String(Math.max(1, durationMs)));
    formData.append('checksum', checksum);
    formData.append('file', blob, `chunk-${chunkIndex}.webm`);
    return retry(() => request.upload<InterviewVideoChunk>(
      `/api/interviews/${sessionId}/videos/chunks`,
      formData,
    ));
  },

  complete(sessionId: string): Promise<VideoUploadCompleteResponse> {
    return request.post<VideoUploadCompleteResponse>(
      `/api/interviews/${sessionId}/videos/complete`,
    );
  },

  list(sessionId: string): Promise<InterviewVideoChunk[]> {
    return request.get<InterviewVideoChunk[]>(`/api/interviews/${sessionId}/videos`);
  },

  listForAdmin(sessionId: string): Promise<InterviewVideoChunk[]> {
    return request.get<InterviewVideoChunk[]>(
      `/api/admin/interviews/${sessionId}/videos`,
    );
  },

  async getPlayableForAdmin(sessionId: string, videoId: number): Promise<PlayableObject> {
    const access = await request.get<ObjectAccessResponse>(
      `/api/admin/interviews/${sessionId}/videos/${videoId}/access`,
    );
    return resolvePlayableObject(access);
  },

  async getCombinedPlayableForAdmin(sessionId: string): Promise<PlayableObject> {
    const response = await request.getInstance().get<Blob>(
      `/api/admin/interviews/${sessionId}/videos/combined/content`,
      {responseType: 'blob'},
    );
    const url = URL.createObjectURL(response.data);
    const durationMs = Number(response.headers['x-video-duration-ms'] || 0);
    return {
      url,
      durationMs: Number.isFinite(durationMs) ? durationMs : undefined,
      revoke: () => URL.revokeObjectURL(url),
    };
  },
};
