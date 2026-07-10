import {request} from './request';
import type {
  InterviewViolationConclusion,
  InterviewVisionEvent,
} from './interviewVision';

export type {InterviewViolationConclusion} from './interviewVision';

export interface EnterpriseInterviewReport {
  sessionId: string;
  assignmentId: number | null;
  overallScore: number;
  technicalScore: number;
  communicationScore: number;
  jobMatchScore: number;
  strengths: string[];
  weaknesses: string[];
  riskNotes: string[];
  summary: string;
  recommendation: string;
  violationConclusion: InterviewViolationConclusion;
  objectiveVisionEvents: InterviewVisionEvent[];
  generatedAt: string;
}

export const interviewReportApi = {
  getForAdmin(sessionId: string): Promise<EnterpriseInterviewReport> {
    return request.get<EnterpriseInterviewReport>(
      `/api/admin/interviews/${sessionId}/report`,
      {timeout: 180000},
    );
  },

  getForCandidate(sessionId: string): Promise<EnterpriseInterviewReport> {
    return request.get<EnterpriseInterviewReport>(
      `/api/interviews/${sessionId}/report`,
      {timeout: 180000},
    );
  },
};
