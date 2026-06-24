import request from './request';

export type JobStatus = 'DRAFT' | 'ACTIVE' | 'CLOSED';
export type AssignmentStatus =
  | 'PENDING'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'EXPIRED'
  | 'CANCELLED';

export interface PagedResponse<T> {
  items: T[];
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
}

export interface JobPosition {
  id: number;
  name: string;
  description: string;
  requirements: string;
  level: string;
  fixedQuestions?: Array<{
    questionIndex: number;
    question: string;
    type: string;
    category: string;
  }>;
  status: JobStatus;
  createdBy: number;
  createdAt: string;
  updatedAt: string;
}

export interface JobPositionPayload {
  name: string;
  description: string;
  requirements: string;
  level: string;
  status: JobStatus;
}

export interface InterviewAssignment {
  id: number;
  candidateId: number;
  candidateName: string;
  candidateMobile?: string;
  jobId: number;
  jobName: string;
  jobLevel?: string;
  resumeId?: number;
  resumeFilename?: string;
  status: AssignmentStatus;
  availableFrom: string;
  deadline: string;
  reportVisibleToCandidate: boolean;
  createdAt: string;
}

export interface CreateAssignmentPayload {
  candidateId: number;
  jobId: number;
  resumeId?: number;
  availableFrom?: string;
  deadline: string;
  reportVisibleToCandidate: boolean;
}

export interface CandidateResume {
  id: number;
  filename: string;
  questionPrepareStatus?: string;
  uploadedAt: string;
}

export const recruitmentApi = {
  listJobs: (page = 0, size = 100) =>
    request.get<PagedResponse<JobPosition>>('/api/admin/jobs', { params: { page, size } }),
  createJob: (payload: JobPositionPayload) =>
    request.post<JobPosition>('/api/admin/jobs', payload),
  updateJob: (jobId: number, payload: JobPositionPayload) =>
    request.put<JobPosition>(`/api/admin/jobs/${jobId}`, payload),
  deleteJob: (jobId: number) => request.delete<void>(`/api/admin/jobs/${jobId}`),
  listAssignments: (page = 0, size = 100) =>
    request.get<PagedResponse<InterviewAssignment>>(
      '/api/admin/interview-assignments', { params: { page, size } }),
  createAssignment: (payload: CreateAssignmentPayload) =>
    request.post<InterviewAssignment>('/api/admin/interview-assignments', payload),
  listCandidateResumes: (candidateId: number) =>
    request.get<CandidateResume[]>(`/api/admin/users/${candidateId}/resumes`),
  listMyAssignments: () =>
    request.get<InterviewAssignment[]>('/api/interviewee/assignments'),
  getMyAssignment: (assignmentId: number) =>
    request.get<InterviewAssignment>(`/api/interviewee/assignments/${assignmentId}`),
  listAvailableJobs: () =>
    request.get<JobPosition[]>('/api/interviewee/jobs'),
};
