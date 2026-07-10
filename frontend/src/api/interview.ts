import { request } from './request';
import type {
  CreateInterviewRequest,
  CurrentQuestionResponse,
  InterviewReport,
  InterviewFlowStatusResponse,
  InterviewSession,
  SubmitAnswerRequest,
  SubmitAnswerResponse
} from '../types/interview';

export interface TextSessionMeta {
  sessionId: string;
  skillId: string;
  difficulty: string;
  resumeId: number | null;
  jobId: number | null;
  jobName: string;
  totalQuestions: number;
  executionStatus: string;
  status: 'INCOMPLETE' | 'UNDER_MANUAL_REVIEW' | 'PASSED' | 'REJECTED';
  /** @deprecated No longer returned for interview records. */
  evaluateStatus?: string | null;
  /** @deprecated No longer returned for interview records. */
  evaluateError?: string | null;
  /** @deprecated No longer returned for interview records. */
  overallScore?: number | null;
  createdAt: string;
  completedAt: string | null;
}

export const interviewApi = {
  /**
   * 列出所有文字面试会话
   */
  async listSessions(): Promise<TextSessionMeta[]> {
    return request.get<TextSessionMeta[]>('/api/interview/sessions');
  },

  /**
   * 创建面试会话
   */
  async createSession(req: CreateInterviewRequest): Promise<InterviewSession> {
    return request.post<InterviewSession>('/api/interview/sessions', req, {
      timeout: 15000,
    });
  },

  /**
   * 获取会话信息
   */
  async getSession(sessionId: string): Promise<InterviewSession> {
    return request.get<InterviewSession>(`/api/interview/sessions/${sessionId}`);
  },

  async retryQuestionPreparation(sessionId: string): Promise<InterviewSession> {
    return request.post<InterviewSession>(
      `/api/interview/sessions/${sessionId}/questions/retry`,
    );
  },

  /**
   * 获取当前问题
   */
  async getCurrentQuestion(sessionId: string): Promise<CurrentQuestionResponse> {
    return request.get<CurrentQuestionResponse>(`/api/interview/sessions/${sessionId}/question`);
  },

  /**
   * 提交答案
   */
  async submitAnswer(req: SubmitAnswerRequest): Promise<SubmitAnswerResponse> {
    return request.post<SubmitAnswerResponse>(
      `/api/interview/sessions/${req.sessionId}/answers`,
      { questionIndex: req.questionIndex, answer: req.answer },
      {
        timeout: 180000, // 3分钟超时
        headers: { 'Idempotency-Key': crypto.randomUUID() },
      }
    );
  },

  /**
   * 获取面试报告
   */
  async getReport(sessionId: string): Promise<InterviewReport> {
    return request.get<InterviewReport>(`/api/interview/sessions/${sessionId}/report`, {
      timeout: 180000, // 3分钟超时，AI评估需要时间
    });
  },

  /**
   * 查找未完成的面试会话
   */
  async findUnfinishedSession(resumeId: number): Promise<InterviewSession | null> {
    try {
      return await request.get<InterviewSession>(`/api/interview/sessions/unfinished/${resumeId}`);
    } catch {
      // 如果没有未完成的会话，返回null
      return null;
    }
  },

  /**
   * 暂存答案（不进入下一题）
   */
  async saveAnswer(req: SubmitAnswerRequest): Promise<void> {
    return request.put<void>(
      `/api/interview/sessions/${req.sessionId}/answers`,
      { questionIndex: req.questionIndex, answer: req.answer }
    );
  },

  /**
   * 提前交卷
   */
  async completeInterview(sessionId: string): Promise<void> {
    return request.post<void>(`/api/interview/sessions/${sessionId}/complete`);
  },

  async confirmDeviceReady(sessionId: string): Promise<InterviewFlowStatusResponse> {
    return request.post<InterviewFlowStatusResponse>(
      `/api/interviews/${sessionId}/device-check`,
      {cameraReady: true, microphoneReady: true},
    );
  },

  async getFlowStatus(sessionId: string): Promise<InterviewFlowStatusResponse> {
    return request.get<InterviewFlowStatusResponse>(
      `/api/interview/sessions/${sessionId}/status`
    );
  },

  async pauseInterview(sessionId: string): Promise<void> {
    return request.post<void>(`/api/interview/sessions/${sessionId}/pause`);
  },

  async resumeInterview(sessionId: string): Promise<void> {
    return request.post<void>(`/api/interview/sessions/${sessionId}/resume`);
  },
};
