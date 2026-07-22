import {request} from './request';
import type {ObjectAccessResponse, PlayableObject} from './interviewVideos';

export type VisionEventType =
  | 'FACE_MISSING'
  | 'MULTIPLE_FACES'
  | 'CAMERA_INTERRUPTED'
  | 'LOW_LIGHT'
  | 'IDENTITY_MISMATCH'
  | 'TAB_HIDDEN'
  | 'WINDOW_BLUR'
  | 'FULLSCREEN_EXIT'
  | 'SCREEN_SHARE_STOPPED'
  | 'SCREEN_CAPTURED';

export type VisionMonitoringState = 'NORMAL' | 'SUSPECT' | 'CONFIRMED';

export interface VisionAnalysisResult {
  facePresent: boolean;
  faceCount: number;
  confidence: number;
  lowLight: boolean;
  cameraActive: boolean;
  identitySimilarity: number | null;
  samePerson: boolean | null;
  monitoringState: VisionMonitoringState;
  recommendedIntervalMs: number;
  candidateEvents: VisionEventType[];
  events: VisionEventType[];
}

export interface InterviewVisionEvent {
  id: number;
  sessionId: string;
  eventType: VisionEventType;
  eventTypes: VisionEventType[];
  occurredAt: string;
  endedAt: string | null;
  durationMs: number | null;
  videoOffsetMs: number | null;
  metadataJson: string | null;
  episodeClosed: boolean;
  evidenceAvailable: boolean;
}

export interface InterviewViolationConclusion {
  verdict: 'NORMAL' | 'VIOLATION';
  violated: boolean;
  directRuleTriggered: boolean;
  screenSwitchCount: number;
  anomalyEpisodeCount: number;
  effectiveAnomalyCount: number;
  weightedAnomalyDurationMs: number;
  riskScore: number;
  reasons: string[];
}

export const interviewVisionApi = {
  analyze(
    sessionId: string,
    frame: Blob | null,
    brightness: number | null,
    cameraActive: boolean,
    videoOffsetMs?: number,
  ): Promise<VisionAnalysisResult> {
    const formData = new FormData();
    if (frame) formData.append('frame', frame, 'vision-frame.jpg');
    if (brightness != null) formData.append('brightness', brightness.toFixed(2));
    formData.append('cameraActive', String(cameraActive));
    if (videoOffsetMs != null) formData.append('videoOffsetMs', String(videoOffsetMs));
    return request.upload<VisionAnalysisResult>(
      `/api/interviews/${sessionId}/vision/analyze`,
      formData,
    );
  },

  listForAdmin(sessionId: string): Promise<InterviewVisionEvent[]> {
    return request.get<InterviewVisionEvent[]>(
      `/api/admin/interviews/${sessionId}/vision-events`,
    );
  },

  getViolationConclusionForAdmin(
    sessionId: string,
  ): Promise<InterviewViolationConclusion> {
    return request.get<InterviewViolationConclusion>(
      `/api/admin/interviews/${sessionId}/violation-conclusion`,
    );
  },

  async getEvidenceForAdmin(sessionId: string, eventId: number): Promise<PlayableObject> {
    const access = await request.get<ObjectAccessResponse>(
      `/api/admin/interviews/${sessionId}/vision-events/${eventId}/access`,
    );
    if (access.direct) return {url: access.url, revoke: () => undefined};
    const response = await request.getInstance().get<Blob>(access.url, {responseType: 'blob'});
    const url = URL.createObjectURL(response.data);
    return {url, revoke: () => URL.revokeObjectURL(url)};
  },

  recordProctorEvent(
    sessionId: string,
    clientEventId: string,
    eventType: VisionEventType,
    evidence?: Blob,
    metadata?: Record<string, unknown>,
    videoOffsetMs?: number,
  ): Promise<void> {
    const formData = new FormData();
    formData.append('clientEventId', clientEventId);
    formData.append('eventType', eventType);
    if (evidence) formData.append('evidence', evidence, 'screen-evidence.jpg');
    if (metadata) formData.append('metadata', JSON.stringify(metadata));
    if (videoOffsetMs != null) formData.append('videoOffsetMs', String(videoOffsetMs));
    return request.upload<void>(
      `/api/interviews/${sessionId}/proctor/events`,
      formData,
    );
  },
};
