import type { InterviewReviewStatus } from '../api/history';

export const interviewReviewStatusLabel: Record<InterviewReviewStatus, string> = {
  INCOMPLETE: '未完成',
  UNDER_MANUAL_REVIEW: '人工审核中',
  PASSED: '通过',
  REJECTED: '不通过',
};

export const interviewReviewStatusClass: Record<InterviewReviewStatus, string> = {
  INCOMPLETE: 'bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-300',
  UNDER_MANUAL_REVIEW: 'bg-amber-50 text-amber-700 dark:bg-amber-950/40 dark:text-amber-300',
  PASSED: 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950/40 dark:text-emerald-300',
  REJECTED: 'bg-red-50 text-red-600 dark:bg-red-950/40 dark:text-red-300',
};
