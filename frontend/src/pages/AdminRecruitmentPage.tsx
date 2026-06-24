import { type FormEvent, useCallback, useEffect, useMemo, useState } from 'react';
import { BriefcaseBusiness, ClipboardList, Loader2, Plus, RefreshCw, X } from 'lucide-react';
import { adminUsersApi, type AdminUser } from '../api/adminUsers';
import { getErrorMessage } from '../api/request';
import {
  recruitmentApi,
  type CandidateResume,
  type InterviewAssignment,
  type JobPosition,
  type JobPositionPayload,
  type JobStatus,
} from '../api/recruitment';
import {loadPageData, readPageData} from '../utils/pageDataCache';

type Tab = 'jobs' | 'assignments';

interface RecruitmentPageData {
  jobs: JobPosition[];
  assignments: InterviewAssignment[];
  candidates: AdminUser[];
}

const RECRUITMENT_CACHE_KEY = 'admin:recruitment';

export function preloadAdminRecruitmentPage(): Promise<RecruitmentPageData> {
  return loadPageData(RECRUITMENT_CACHE_KEY, async () => {
    const [jobPage, assignmentPage, userPage] = await Promise.all([
      recruitmentApi.listJobs(),
      recruitmentApi.listAssignments(),
      adminUsersApi.list(0, 100),
    ]);
    return {
      jobs: jobPage.items,
      assignments: assignmentPage.items,
      candidates: userPage.items.filter(user =>
        user.roles.includes('INTERVIEWEE') && user.status === 'ACTIVE'),
    };
  });
}

const jobStatusLabel: Record<JobStatus, string> = {
  DRAFT: '草稿',
  ACTIVE: '招聘中',
  CLOSED: '已关闭',
};

export default function AdminRecruitmentPage() {
  const cached = readPageData<RecruitmentPageData>(RECRUITMENT_CACHE_KEY);
  const [tab, setTab] = useState<Tab>('jobs');
  const [jobs, setJobs] = useState<JobPosition[]>(cached?.jobs ?? []);
  const [assignments, setAssignments] = useState<InterviewAssignment[]>(
    cached?.assignments ?? []);
  const [candidates, setCandidates] = useState<AdminUser[]>(cached?.candidates ?? []);
  const [loading, setLoading] = useState(cached == null);
  const [error, setError] = useState('');
  const [showJobForm, setShowJobForm] = useState(false);
  const [showAssignmentForm, setShowAssignmentForm] = useState(false);

  const load = useCallback(async (force = false) => {
    setLoading(readPageData<RecruitmentPageData>(RECRUITMENT_CACHE_KEY) == null);
    setError('');
    try {
      const pageData = force
        ? await loadPageData(RECRUITMENT_CACHE_KEY, async () => {
            const [jobPage, assignmentPage, userPage] = await Promise.all([
              recruitmentApi.listJobs(),
              recruitmentApi.listAssignments(),
              adminUsersApi.list(0, 100),
            ]);
            return {
              jobs: jobPage.items,
              assignments: assignmentPage.items,
              candidates: userPage.items.filter(user =>
                user.roles.includes('INTERVIEWEE') && user.status === 'ACTIVE'),
            };
          }, {force: true})
        : await preloadAdminRecruitmentPage();
      setJobs(pageData.jobs);
      setAssignments(pageData.assignments);
      setCandidates(pageData.candidates);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <div className="max-w-6xl mx-auto">
      <div className="flex items-start justify-between gap-4 mb-7">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-white">岗位与面试任务</h1>
          <p className="text-sm text-slate-500 mt-1">维护招聘岗位，并向面试者分配正式面试任务</p>
        </div>
        <button
          onClick={() => void load(true)}
          className="inline-flex items-center gap-2 px-4 py-2 rounded-lg border border-slate-200 bg-white text-slate-600"
        >
          <RefreshCw className="w-4 h-4" />刷新
        </button>
      </div>

      <div className="flex gap-2 mb-6">
        <TabButton active={tab === 'jobs'} onClick={() => setTab('jobs')}>
          <BriefcaseBusiness className="w-4 h-4" />岗位管理
        </TabButton>
        <TabButton active={tab === 'assignments'} onClick={() => setTab('assignments')}>
          <ClipboardList className="w-4 h-4" />面试任务
        </TabButton>
      </div>

      {error && <div className="mb-5 px-4 py-3 rounded-lg bg-red-50 text-red-600">{error}</div>}

      {loading ? (
        <div className="h-64 flex items-center justify-center">
          <Loader2 className="w-8 h-8 text-primary-500 animate-spin" />
        </div>
      ) : tab === 'jobs' ? (
        <JobsPanel
          jobs={jobs}
          onCreate={() => setShowJobForm(true)}
          onChanged={() => load(true)}
        />
      ) : (
        <AssignmentsPanel
          assignments={assignments}
          onCreate={() => setShowAssignmentForm(true)}
        />
      )}

      {showJobForm && (
        <JobForm
          onClose={() => setShowJobForm(false)}
          onCreated={async () => {
            setShowJobForm(false);
            await load(true);
          }}
        />
      )}
      {showAssignmentForm && (
        <AssignmentForm
          jobs={jobs.filter(job => job.status === 'ACTIVE')}
          candidates={candidates}
          onClose={() => setShowAssignmentForm(false)}
          onCreated={async () => {
            setShowAssignmentForm(false);
            setTab('assignments');
            await load(true);
          }}
        />
      )}
    </div>
  );
}

function JobsPanel({
  jobs,
  onCreate,
  onChanged,
}: {
  jobs: JobPosition[];
  onCreate: () => void;
  onChanged: () => Promise<void>;
}) {
  const [busyId, setBusyId] = useState<number | null>(null);
  const [error, setError] = useState('');

  const changeStatus = async (job: JobPosition, status: JobStatus) => {
    setBusyId(job.id);
    setError('');
    try {
      await recruitmentApi.updateJob(job.id, {
        name: job.name,
        description: job.description,
        requirements: job.requirements,
        level: job.level,
        status,
      });
      await onChanged();
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setBusyId(null);
    }
  };

  return (
    <>
      <div className="flex justify-between items-center mb-4">
        <p className="text-sm text-slate-500">共 {jobs.length} 个岗位</p>
        <button onClick={onCreate} className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-primary-500 text-white">
          <Plus className="w-4 h-4" />新建岗位
        </button>
      </div>
      {error && <div className="mb-4 text-sm text-red-600">{error}</div>}
      <div className="grid gap-4">
        {jobs.map(job => (
          <div key={job.id} className="rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 p-5">
            <div className="flex items-start justify-between gap-4">
              <div>
                <div className="flex items-center gap-3">
                  <h2 className="font-bold text-slate-900 dark:text-white">{job.name}</h2>
                  <span className="px-2 py-1 rounded-full bg-primary-50 text-primary-600 text-xs">
                    {jobStatusLabel[job.status]}
                  </span>
                  <span className="text-xs text-slate-400">{job.level}</span>
                </div>
                <p className="mt-3 text-sm text-slate-600 dark:text-slate-300 whitespace-pre-wrap">{job.description}</p>
                <p className="mt-2 text-sm text-slate-500 whitespace-pre-wrap">要求：{job.requirements}</p>
              </div>
              <select
                value={job.status}
                disabled={busyId === job.id}
                onChange={event => void changeStatus(job, event.target.value as JobStatus)}
                className="px-3 py-2 rounded-lg border border-slate-200 bg-white dark:bg-slate-950"
              >
                <option value="DRAFT">草稿</option>
                <option value="ACTIVE">招聘中</option>
                <option value="CLOSED">关闭</option>
              </select>
            </div>
          </div>
        ))}
        {jobs.length === 0 && <Empty text="还没有岗位，先创建第一个招聘岗位" />}
      </div>
    </>
  );
}

function AssignmentsPanel({
  assignments,
  onCreate,
}: {
  assignments: InterviewAssignment[];
  onCreate: () => void;
}) {
  return (
    <>
      <div className="flex justify-between items-center mb-4">
        <p className="text-sm text-slate-500">共 {assignments.length} 个任务</p>
        <button onClick={onCreate} className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-primary-500 text-white">
          <Plus className="w-4 h-4" />分配面试任务
        </button>
      </div>
      <div className="overflow-hidden rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900">
        <table className="w-full text-sm">
          <thead className="bg-slate-50 dark:bg-slate-800 text-slate-500">
            <tr>
              <th className="text-left px-5 py-3">候选人</th>
              <th className="text-left px-5 py-3">岗位</th>
              <th className="text-left px-5 py-3">简历</th>
              <th className="text-left px-5 py-3">截止时间</th>
              <th className="text-left px-5 py-3">状态</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
            {assignments.map(item => (
              <tr key={item.id}>
                <td className="px-5 py-4 font-medium">{item.candidateName}</td>
                <td className="px-5 py-4">{item.jobName}<span className="ml-2 text-xs text-slate-400">{item.jobLevel}</span></td>
                <td className="px-5 py-4 text-slate-500">{item.resumeFilename || '未绑定'}</td>
                <td className="px-5 py-4 text-slate-500">{new Date(item.deadline).toLocaleString()}</td>
                <td className="px-5 py-4"><span className="px-2 py-1 rounded-full bg-slate-100 text-xs">{item.status}</span></td>
              </tr>
            ))}
          </tbody>
        </table>
        {assignments.length === 0 && <Empty text="还没有分配面试任务" />}
      </div>
    </>
  );
}

function JobForm({ onClose, onCreated }: { onClose: () => void; onCreated: () => Promise<void> }) {
  const [form, setForm] = useState<JobPositionPayload>({
    name: '', description: '', requirements: '', level: '', status: 'DRAFT',
  });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setSaving(true);
    setError('');
    try {
      await recruitmentApi.createJob(form);
      await onCreated();
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal title="新建招聘岗位" onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <Input label="岗位名称" value={form.name} onChange={name => setForm({ ...form, name })} />
        <Input label="岗位级别" value={form.level} onChange={level => setForm({ ...form, level })} placeholder="例如：初级 / 中级 / 高级" />
        <TextArea label="岗位描述" value={form.description} onChange={description => setForm({ ...form, description })} />
        <TextArea label="岗位要求" value={form.requirements} onChange={requirements => setForm({ ...form, requirements })} />
        <label className="block text-sm font-medium text-slate-700">初始状态
          <select value={form.status} onChange={event => setForm({ ...form, status: event.target.value as JobStatus })} className="mt-1 w-full px-3 py-2 rounded-lg border border-slate-200">
            <option value="DRAFT">草稿</option><option value="ACTIVE">立即启用</option>
          </select>
        </label>
        {error && <p className="text-sm text-red-600">{error}</p>}
        <SubmitButton saving={saving} text="保存岗位" />
      </form>
    </Modal>
  );
}

function AssignmentForm({
  jobs,
  candidates,
  onClose,
  onCreated,
}: {
  jobs: JobPosition[];
  candidates: AdminUser[];
  onClose: () => void;
  onCreated: () => Promise<void>;
}) {
  const defaultDeadline = useMemo(() => {
    const date = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000);
    return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
  }, []);
  const [candidateId, setCandidateId] = useState('');
  const [jobId, setJobId] = useState('');
  const [resumeId, setResumeId] = useState('');
  const [resumes, setResumes] = useState<CandidateResume[]>([]);
  const [loadingResumes, setLoadingResumes] = useState(false);
  const [deadline, setDeadline] = useState(defaultDeadline);
  const [reportVisible, setReportVisible] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    setResumeId('');
    setResumes([]);
    if (!candidateId) return;
    setLoadingResumes(true);
    recruitmentApi.listCandidateResumes(Number(candidateId))
      .then(setResumes)
      .catch(err => setError(getErrorMessage(err)))
      .finally(() => setLoadingResumes(false));
  }, [candidateId]);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setSaving(true);
    setError('');
    try {
      await recruitmentApi.createAssignment({
        candidateId: Number(candidateId),
        jobId: Number(jobId),
        resumeId: resumeId ? Number(resumeId) : undefined,
        deadline: `${deadline}:00`,
        reportVisibleToCandidate: reportVisible,
      });
      await onCreated();
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal title="分配面试任务" onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <label className="block text-sm font-medium text-slate-700">候选人
          <select required value={candidateId} onChange={event => setCandidateId(event.target.value)} className="mt-1 w-full px-3 py-2 rounded-lg border border-slate-200">
            <option value="">请选择面试者</option>
            {candidates.map(user => <option key={user.id} value={user.id}>{user.nickname || user.username} · {user.mobile || '未绑定手机'}</option>)}
          </select>
        </label>
        <label className="block text-sm font-medium text-slate-700">岗位
          <select required value={jobId} onChange={event => setJobId(event.target.value)} className="mt-1 w-full px-3 py-2 rounded-lg border border-slate-200">
            <option value="">请选择招聘中的岗位</option>
            {jobs.map(job => <option key={job.id} value={job.id}>{job.name} · {job.level}</option>)}
          </select>
        </label>
        <label className="block text-sm font-medium text-slate-700">候选人简历（可选）
          <select
            value={resumeId}
            disabled={!candidateId || loadingResumes}
            onChange={event => setResumeId(event.target.value)}
            className="mt-1 w-full px-3 py-2 rounded-lg border border-slate-200 disabled:bg-slate-100"
          >
            <option value="">{loadingResumes ? '正在加载简历…' : '不绑定简历'}</option>
            {resumes.map(resume => (
              <option key={resume.id} value={resume.id}>
                {resume.filename} · {resume.questionPrepareStatus === 'COMPLETED' ? '已解析' : '待解析'}
              </option>
            ))}
          </select>
        </label>
        <Input label="截止时间" type="datetime-local" value={deadline} onChange={setDeadline} />
        <label className="flex items-center gap-2 text-sm text-slate-700">
          <input type="checkbox" checked={reportVisible} onChange={event => setReportVisible(event.target.checked)} />
          完成后允许候选人查看报告
        </label>
        {error && <p className="text-sm text-red-600">{error}</p>}
        <SubmitButton saving={saving} text="确认分配" />
      </form>
    </Modal>
  );
}

function Modal({ title, children, onClose }: { title: string; children: React.ReactNode; onClose: () => void }) {
  return <div className="fixed inset-0 z-[100] bg-slate-950/60 flex items-center justify-center p-6">
    <div className="w-full max-w-xl max-h-[90vh] overflow-y-auto rounded-2xl bg-white p-6 shadow-2xl">
      <div className="flex items-center justify-between mb-5"><h2 className="text-xl font-bold">{title}</h2><button onClick={onClose} aria-label="关闭"><X className="w-5 h-5" /></button></div>
      {children}
    </div>
  </div>;
}

function Input({ label, value, onChange, placeholder, type = 'text', required = true }: { label: string; value: string; onChange: (value: string) => void; placeholder?: string; type?: string; required?: boolean }) {
  return <label className="block text-sm font-medium text-slate-700">{label}<input type={type} required={required} value={value} onChange={event => onChange(event.target.value)} placeholder={placeholder} className="mt-1 w-full px-3 py-2 rounded-lg border border-slate-200" /></label>;
}

function TextArea({ label, value, onChange }: { label: string; value: string; onChange: (value: string) => void }) {
  return <label className="block text-sm font-medium text-slate-700">{label}<textarea required rows={4} value={value} onChange={event => onChange(event.target.value)} className="mt-1 w-full px-3 py-2 rounded-lg border border-slate-200 resize-y" /></label>;
}

function SubmitButton({ saving, text }: { saving: boolean; text: string }) {
  return <button type="submit" disabled={saving} className="w-full h-11 rounded-lg bg-primary-500 text-white font-semibold disabled:opacity-50">{saving ? '保存中…' : text}</button>;
}

function TabButton({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return <button onClick={onClick} className={`inline-flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium ${active ? 'bg-primary-500 text-white' : 'bg-white border border-slate-200 text-slate-600'}`}>{children}</button>;
}

function Empty({ text }: { text: string }) {
  return <div className="py-16 text-center text-slate-400">{text}</div>;
}
