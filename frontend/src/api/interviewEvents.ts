import { getAccessToken } from '../utils/authStorage';

export type InterviewClientEventType =
  | 'DEVICE_READY'
  | 'START_INTERVIEW'
  | 'ANSWER_STARTED'
  | 'ANSWER_SUBMITTED'
  | 'AUDIO_CHUNK'
  | 'READ_QUESTION'
  | 'VIDEO_CHUNK_UPLOADED'
  | 'PAUSE_REQUEST'
  | 'RESUME_REQUEST'
  | 'FINISH_REQUEST'
  | 'PING';

export type InterviewServerEventType =
  | 'SESSION_STATUS_CHANGED'
  | 'QUESTIONS_READY'
  | 'QUESTIONS_FAILED'
  | 'NEW_QUESTION'
  | 'QUESTION_AUDIO_READY'
  | 'SPEECH_RECOGNITION_READY'
  | 'ANSWER_TRANSCRIPT'
  | 'ANSWER_ACCEPTED'
  | 'EVALUATION_COMPLETED'
  | 'NEXT_QUESTION_PENDING'
  | 'WARNING_EVENT'
  | 'INTERVIEW_FINISHED'
  | 'PONG'
  | 'ERROR';

export interface InterviewSocketEvent {
  eventId: string;
  type: InterviewClientEventType | InterviewServerEventType;
  sessionId: string;
  sequence: number;
  timestamp: string;
  payload: Record<string, unknown>;
}

export interface InterviewEventHandlers {
  onEvent?: (event: InterviewSocketEvent) => void;
  onOpen?: () => void;
  onClose?: (event: CloseEvent) => void;
  onError?: (event: Event) => void;
}

export class InterviewEventSocket {
  private socket: WebSocket | null = null;
  private reconnectAttempts = 0;
  private manuallyClosed = false;
  private heartbeatId?: number;
  private readonly seenEventIds = new Set<string>();

  constructor(
    private readonly sessionId: string,
    private readonly url: string,
    private readonly handlers: InterviewEventHandlers,
  ) {}

  connect() {
    this.manuallyClosed = false;
    const token = getAccessToken();
    const target = new URL(this.url);
    if (token) target.searchParams.set('access_token', token);
    target.searchParams.set('lastSequence', String(this.lastSequence()));
    this.socket = new WebSocket(target.toString());

    this.socket.onopen = () => {
      this.reconnectAttempts = 0;
      this.startHeartbeat();
      this.handlers.onOpen?.();
    };
    this.socket.onmessage = message => this.handleMessage(message.data);
    this.socket.onerror = event => this.handlers.onError?.(event);
    this.socket.onclose = event => {
      this.stopHeartbeat();
      this.handlers.onClose?.(event);
      if (!this.manuallyClosed && this.reconnectAttempts < 5) {
        const delay = Math.min(10_000, 1_000 * 2 ** this.reconnectAttempts);
        this.reconnectAttempts++;
        window.setTimeout(() => this.connect(), delay);
      }
    };
  }

  send(type: InterviewClientEventType, payload: Record<string, unknown> = {}) {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) return false;
    const event: InterviewSocketEvent = {
      eventId: crypto.randomUUID(),
      type,
      sessionId: this.sessionId,
      sequence: 0,
      timestamp: new Date().toISOString(),
      payload,
    };
    this.socket.send(JSON.stringify(event));
    return true;
  }

  waitUntilOpen(timeoutMs = 8_000): Promise<void> {
    if (this.socket?.readyState === WebSocket.OPEN) return Promise.resolve();

    return new Promise((resolve, reject) => {
      const startedAt = Date.now();
      const timer = window.setInterval(() => {
        if (this.socket?.readyState === WebSocket.OPEN) {
          window.clearInterval(timer);
          resolve();
          return;
        }
        if (Date.now() - startedAt >= timeoutMs) {
          window.clearInterval(timer);
          reject(new Error('实时面试连接超时，请检查网络后重试'));
        }
      }, 50);
    });
  }

  close() {
    this.manuallyClosed = true;
    this.stopHeartbeat();
    this.socket?.close(1000, 'User closed interview event connection');
    this.socket = null;
  }

  private handleMessage(raw: string) {
    try {
      const event = JSON.parse(raw) as InterviewSocketEvent;
      if (event.sessionId !== this.sessionId) return;
      const persistedEvent = event.sequence > 0;
      if (this.seenEventIds.has(event.eventId)
        || (persistedEvent && event.sequence <= this.lastSequence())) return;
      this.seenEventIds.add(event.eventId);
      if (this.seenEventIds.size > 500) this.seenEventIds.clear();
      if (persistedEvent) {
        sessionStorage.setItem(this.sequenceKey(), String(event.sequence));
      }
      this.handlers.onEvent?.(event);
    } catch (error) {
      console.error('Invalid interview WebSocket event', error);
    }
  }

  private startHeartbeat() {
    this.stopHeartbeat();
    this.heartbeatId = window.setInterval(() => this.send('PING'), 20_000);
  }

  private stopHeartbeat() {
    if (this.heartbeatId !== undefined) {
      window.clearInterval(this.heartbeatId);
      this.heartbeatId = undefined;
    }
  }

  private lastSequence() {
    return Number(sessionStorage.getItem(this.sequenceKey()) || 0);
  }

  private sequenceKey() {
    return `interview:last-sequence:${this.sessionId}`;
  }
}

export function resolveInterviewWebSocketUrl(path: string) {
  if (path.startsWith('ws://') || path.startsWith('wss://')) return path;
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const configuredBase = import.meta.env.VITE_WS_BASE_URL as string | undefined;
  const base = configuredBase || `${protocol}//${window.location.host}`;
  return `${base.replace(/\/$/, '')}/${path.replace(/^\//, '')}`;
}
