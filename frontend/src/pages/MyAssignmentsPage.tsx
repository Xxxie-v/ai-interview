import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  ArrowRight,
  BriefcaseBusiness,
  FileStack,
  Loader2,
  MapPin,
  Upload,
} from 'lucide-react';
import { getErrorMessage } from '../api/request';
import { historyApi, type ResumeListItem } from '../api/history';
import { interviewApi } from '../api/interview';
import { recruitmentApi, type JobPosition } from '../api/recruitment';

function difficultyFromLevel(level: string) {
  const normalized = level.toLowerCase();
  if (normalized.includes('高级') || normalized.includes('senior')) return 'senior';
  if (normalized.includes('初级') || normalized.includes('junior')) return 'junior';
  return 'mid';
}

export default function MyAssignmentsPage() {
  const navigate = useNavigate();
  const [jobs, setJobs] = useState<JobPosition[]>([]);
  const [resumes, setResumes] = useState<ResumeListItem[]>([]);
  const [selectedResumeId, setSelectedResumeId] = useState<number>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [startingJobId, setStartingJobId] = useState<number>();

  useEffect(() => {
    Promise.all([
      recruitmentApi.listAvailableJobs(),
      historyApi.getResumes(),
    ])
      .then(([availableJobs, resumeList]) => {
        setJobs(availableJobs);
        setResumes(resumeList);
        setSelectedResumeId(resumeList[0]?.id);
      })
      .catch(err => setError(getErrorMessage(err)))
      .finally(() => setLoading(false));
  }, []);

  const startInterview = async (job: JobPosition) => {
    if (!selectedResumeId) return;
    setStartingJobId(job.id);
    setError('');
    try {
      const interviewConfig = {
        jobId: job.id,
        skillId: 'custom',
        difficulty: difficultyFromLevel(job.level),
        questionCount: 8,
        officialInterview: true,
      } as const;
      const session = await interviewApi.createSession({
        resumeText: '',
        resumeId: selectedResumeId,
        ...interviewConfig,
      });
      navigate('/interview', {
        state: {
          resumeId: selectedResumeId,
          sessionIdToResume: session.sessionId,
          interviewConfig,
        },
      });
    } catch (cause) {
      setError(getErrorMessage(cause));
    } finally {
      setStartingJobId(undefined);
    }
  };

  if (loading) {
    return (
      <div className="h-64 flex items-center justify-center">
        <Loader2 className="w-8 h-8 animate-spin text-primary-500" />
      </div>
    );
  }

  const hasResume = resumes.length > 0;

  return (
    <div className="max-w-6xl mx-auto">
      <div className="mb-7">
        <h1 className="text-2xl font-bold text-slate-900 dark:text-white flex items-center gap-3">
          <BriefcaseBusiness className="w-7 h-7 text-primary-500" />
          招聘岗位
        </h1>
        <p className="mt-1 text-sm text-slate-500">
          选择目标岗位，系统将结合岗位要求和你的简历生成 AI 面试题
        </p>
      </div>

      {error && (
        <div className="mb-5 px-4 py-3 rounded-lg bg-red-50 dark:bg-red-900/20 text-red-600">
          {error}
        </div>
      )}

      {!hasResume ? (
        <div className="mb-7 rounded-2xl border border-amber-200 dark:border-amber-800/50 bg-amber-50 dark:bg-amber-900/20 p-6">
          <div className="flex flex-col sm:flex-row sm:items-center gap-4">
            <div className="w-12 h-12 rounded-xl bg-amber-100 dark:bg-amber-900/40 flex items-center justify-center">
              <FileStack className="w-6 h-6 text-amber-600" />
            </div>
            <div className="flex-1">
              <h2 className="font-bold text-slate-900 dark:text-white">请先上传简历</h2>
              <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">
                AI 需要结合你的简历和岗位 JD 生成针对性问题，上传后才可以开始面试。
              </p>
            </div>
            <Link
              to="/upload"
              className="inline-flex items-center justify-center gap-2 px-5 py-2.5 rounded-xl bg-amber-500 hover:bg-amber-600 text-white font-semibold text-sm"
            >
              <Upload className="w-4 h-4" />
              上传简历
            </Link>
          </div>
        </div>
      ) : (
        <div className="mb-7 rounded-2xl border border-primary-100 dark:border-primary-800/40 bg-primary-50/70 dark:bg-primary-900/20 p-5">
          <label className="block text-sm font-semibold text-slate-800 dark:text-slate-100 mb-2">
            本次面试使用的简历
          </label>
          <div className="flex flex-col sm:flex-row gap-3">
            <select
              value={selectedResumeId ?? ''}
              onChange={event => setSelectedResumeId(Number(event.target.value))}
              className="flex-1 px-4 py-2.5 rounded-xl border border-primary-200 dark:border-primary-700 bg-white dark:bg-slate-800 text-sm"
            >
              {resumes.map(resume => (
                <option key={resume.id} value={resume.id}>
                  {resume.filename}
                </option>
              ))}
            </select>
            <Link
              to="/history"
              className="inline-flex items-center justify-center gap-2 px-4 py-2.5 rounded-xl border border-primary-200 dark:border-primary-700 text-primary-600 text-sm font-medium bg-white dark:bg-slate-800"
            >
              <FileStack className="w-4 h-4" />
              管理简历
            </Link>
          </div>
        </div>
      )}

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
        {jobs.map(job => (
          <article
            key={job.id}
            className="rounded-2xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 p-6 shadow-sm"
          >
            <div className="flex items-start justify-between gap-4">
              <div>
                <h2 className="text-lg font-bold text-slate-900 dark:text-white">{job.name}</h2>
                <div className="mt-2 flex items-center gap-2 text-xs text-slate-500">
                  <MapPin className="w-3.5 h-3.5" />
                  岗位级别：{job.level || '未设置'}
                </div>
              </div>
              <span className="px-2.5 py-1 rounded-full bg-emerald-50 dark:bg-emerald-900/30 text-emerald-600 text-xs font-medium">
                招聘中
              </span>
            </div>

            <div className="mt-5 space-y-4 text-sm">
              <div>
                <h3 className="font-semibold text-slate-700 dark:text-slate-200">岗位介绍</h3>
                <p className="mt-1 text-slate-500 dark:text-slate-400 whitespace-pre-line line-clamp-3">
                  {job.description}
                </p>
              </div>
              <div>
                <h3 className="font-semibold text-slate-700 dark:text-slate-200">任职要求</h3>
                <p className="mt-1 text-slate-500 dark:text-slate-400 whitespace-pre-line line-clamp-3">
                  {job.requirements}
                </p>
              </div>
            </div>

            <button
              type="button"
              disabled={!hasResume || !selectedResumeId || startingJobId !== undefined}
              onClick={() => void startInterview(job)}
              className="mt-6 w-full inline-flex items-center justify-center gap-2 px-5 py-3 rounded-xl bg-primary-500 hover:bg-primary-600 text-white font-semibold text-sm disabled:opacity-45 disabled:cursor-not-allowed"
            >
              {startingJobId === job.id
                ? <Loader2 className="w-4 h-4 animate-spin" />
                : <ArrowRight className="w-4 h-4" />}
              {startingJobId === job.id
                ? '正在创建面试…'
                : hasResume ? '参加 AI 面试' : '上传简历后可面试'}
            </button>
          </article>
        ))}
      </div>

      {jobs.length === 0 && (
        <div className="py-20 text-center rounded-2xl border border-dashed border-slate-300 dark:border-slate-700 text-slate-400">
          暂时没有招聘中的岗位
        </div>
      )}
    </div>
  );
}
