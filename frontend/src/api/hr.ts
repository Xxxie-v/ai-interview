import request from './request';
import type { InterviewReviewStatus } from './history';

export interface HrInterviewResult {
  sessionId: string;
  resumeId: number | null;
  jobId: number | null;
  jobName: string;
  candidateId: number;
  candidateName: string;
  candidatePhone: string | null;
  resumeFilename: string | null;
  skillId: string;
  difficulty: string;
  status: InterviewReviewStatus;
  createdAt: string;
  completedAt: string | null;
}

export interface HrInterviewResultPage {
  items: HrInterviewResult[];
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
}

export const hrApi = {
  listInterviewResults: (page = 0, size = 20) => request.get<HrInterviewResultPage>(
    '/api/hr/interview-results',
    {params: {page, size}},
  ),

  updateReviewStatus: (
    sessionId: string,
    status: InterviewReviewStatus,
  ) => request.patch<HrInterviewResult>(
    `/api/hr/interview-results/${sessionId}/review-status`,
    { status },
  ),

  async downloadResume(sessionId: string): Promise<Blob> {
    const response = await request.getInstance().get(`/api/hr/interview-results/${sessionId}/resume`, {
      responseType: 'blob',
      skipResultTransform: true,
    } as never);
    return response.data;
  },
};
