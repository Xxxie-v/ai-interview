import { Fragment, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  BriefcaseBusiness,
  ChevronDown,
  Download,
  Eye,
  Loader2,
  RefreshCw,
  Video,
  X,
} from 'lucide-react';
import {
  hrApi,
  type HrInterviewResult,
  type HrInterviewResultPage,
} from '../api/hr';
import {
  interviewVisionApi,
  type InterviewViolationConclusion,
  type InterviewVisionEvent,
  type VisionEventType,
} from '../api/interviewVision';
import {
  interviewReportApi,
  type EnterpriseInterviewReport,
} from '../api/interviewReports';
import { getErrorMessage } from '../api/request';
import { formatDateTime } from '../utils/date';
import type { InterviewReviewStatus } from '../api/history';
import {
  interviewReviewStatusClass,
  interviewReviewStatusLabel,
} from '../utils/interviewReviewStatus';
import {loadPageData, readPageData, writePageData} from '../utils/pageDataCache';
import {
  interviewVideoApi,
  type InterviewVideoChunk,
  type PlayableObject,
} from '../api/interviewVideos';

const PAGE_SIZE = 20;
const resultsCacheKey = (page: number) => `admin:hr-results:${page}:${PAGE_SIZE}`;

export function preloadHrInterviewResultsPage(): Promise<HrInterviewResultPage> {
  return loadPageData(resultsCacheKey(0), () => hrApi.listInterviewResults(0, PAGE_SIZE));
}

interface CandidateGroup {
  groupKey: string;
  jobId: number | null;
  jobName: string;
  candidateId: number;
  candidateName: string;
  candidatePhone: string | null;
  results: HrInterviewResult[];
  latestResult: HrInterviewResult;
}

const VISION_EVENT_LABELS: Record<VisionEventType, string> = {
  FACE_MISSING: '人脸暂时离开画面',
  MULTIPLE_FACES: '画面中出现多人',
  CAMERA_INTERRUPTED: '摄像头中断',
  LOW_LIGHT: '画面光线过暗',
  IDENTITY_MISMATCH: '检测到疑似非本人',
  TAB_HIDDEN: '切换到其他标签页',
  WINDOW_BLUR: '面试窗口失去焦点',
  FULLSCREEN_EXIT: '退出全屏模式',
  SCREEN_SHARE_STOPPED: '停止屏幕共享',
  SCREEN_CAPTURED: '屏幕监控采样',
};

function visionEventTypes(event: InterviewVisionEvent): VisionEventType[] {
  return event.eventTypes?.length ? event.eventTypes : [event.eventType];
}

function visionEventSummary(event: InterviewVisionEvent): string {
  return visionEventTypes(event).map(type => VISION_EVENT_LABELS[type]).join('、');
}

function formatVisionDuration(durationMs: number | null): string {
  if (durationMs == null) return '';
  if (durationMs < 1_000) return `${durationMs}ms`;
  return `${(durationMs / 1_000).toFixed(1)}s`;
}

function ViolationConclusionCard({
  conclusion,
}: {
  conclusion: InterviewViolationConclusion;
}) {
  return (
    <div className={`rounded-xl border p-4 ${conclusion.violated
      ? 'border-red-200 bg-red-50 dark:border-red-800 dark:bg-red-950/30'
      : 'border-emerald-200 bg-emerald-50 dark:border-emerald-800 dark:bg-emerald-950/30'
    }`}>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <span className={`font-semibold ${conclusion.violated
          ? 'text-red-700 dark:text-red-300'
          : 'text-emerald-700 dark:text-emerald-300'
        }`}>
          违规结论：{conclusion.violated ? '违规' : '未违规'}
        </span>
        <span className="text-xs text-slate-600 dark:text-slate-300">
          风险 {conclusion.riskScore} 分 · 切屏 {conclusion.screenSwitchCount} 次 · 异常时段{' '}
          {conclusion.anomalyEpisodeCount} 个
        </span>
      </div>
      <ul className="mt-2 space-y-1 text-xs text-slate-600 dark:text-slate-300">
        {conclusion.reasons.map(reason => (
          <li key={reason}>• {reason}</li>
        ))}
      </ul>
    </div>
  );
}

function groupByCandidate(results: HrInterviewResult[]): CandidateGroup[] {
  const groups = new Map<string, HrInterviewResult[]>();
  for (const result of results) {
    const groupKey = `${result.jobId ?? 'none'}:${result.candidateId}`;
    const list = groups.get(groupKey) ?? [];
    list.push(result);
    groups.set(groupKey, list);
  }

  return Array.from(groups.entries())
    .map(([groupKey, items]) => {
      const sorted = [...items].sort((a, b) =>
        new Date(b.completedAt || b.createdAt).getTime()
        - new Date(a.completedAt || a.createdAt).getTime());
      const latestResult = sorted[0];
      return {
        groupKey,
        jobId: latestResult.jobId,
        jobName: latestResult.jobName,
        candidateId: latestResult.candidateId,
        candidateName: latestResult.candidateName,
        candidatePhone: latestResult.candidatePhone,
        results: sorted,
        latestResult,
      };
    })
    .sort((a, b) => a.jobName.localeCompare(b.jobName, 'zh-CN')
      || new Date(b.latestResult.completedAt || b.latestResult.createdAt).getTime()
      - new Date(a.latestResult.completedAt || a.latestResult.createdAt).getTime());
}

export default function HrInterviewResultsPage() {
  const navigate = useNavigate();
  const [page, setPage] = useState(0);
  const [resultPage, setResultPage] = useState<HrInterviewResultPage | null>(() =>
    readPageData<HrInterviewResultPage>(resultsCacheKey(0)));
  const [loading, setLoading] = useState(() =>
    readPageData<HrInterviewResultPage>(resultsCacheKey(0)) == null);
  const [downloadingSessionId, setDownloadingSessionId] = useState<string | null>(null);
  const [expandedCandidateId, setExpandedCandidateId] = useState<string | null>(null);
  const [visibleVisionSessionId, setVisibleVisionSessionId] = useState<string | null>(null);
  const [loadingVisionSessionId, setLoadingVisionSessionId] = useState<string | null>(null);
  const [visionEventsBySession, setVisionEventsBySession] = useState<
    Record<string, InterviewVisionEvent[]>
  >({});
  const [violationConclusionsBySession, setViolationConclusionsBySession] = useState<
    Record<string, InterviewViolationConclusion>
  >({});
  const [visibleVideoSessionId, setVisibleVideoSessionId] = useState<string | null>(null);
  const [loadingVideoSessionId, setLoadingVideoSessionId] = useState<string | null>(null);
  const [videosBySession, setVideosBySession] = useState<
    Record<string, InterviewVideoChunk[]>
  >({});
  const [activeMedia, setActiveMedia] = useState<(
    PlayableObject & {
      kind: 'video' | 'image';
      title: string;
      clipStartMs?: number;
      clipEndMs?: number;
    }
  ) | null>(null);
  const videoCacheRef = useRef(new Map<string, PlayableObject>());
  const [loadingEvidenceId, setLoadingEvidenceId] = useState<string | null>(null);
  const [visibleReportSessionId, setVisibleReportSessionId] = useState<string | null>(null);
  const [loadingReportSessionId, setLoadingReportSessionId] = useState<string | null>(null);
  const [reportsBySession, setReportsBySession] = useState<
    Record<string, EnterpriseInterviewReport>
  >({});
  const [error, setError] = useState('');
  const [updatingSessionId, setUpdatingSessionId] = useState<string | null>(null);

  const candidateGroups = useMemo(
    () => groupByCandidate(resultPage?.items ?? []),
    [resultPage?.items],
  );

  const loadResults = async (targetPage: number, force = false) => {
    const cacheKey = resultsCacheKey(targetPage);
    setLoading(readPageData<HrInterviewResultPage>(cacheKey) == null);
    setError('');
    try {
      setResultPage(await loadPageData(
        cacheKey,
        () => hrApi.listInterviewResults(targetPage, PAGE_SIZE),
        {force},
      ));
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  };

  const updateReviewStatus = async (
    sessionId: string,
    status: InterviewReviewStatus,
  ) => {
    setUpdatingSessionId(sessionId);
    setError('');
    try {
      const updated = await hrApi.updateReviewStatus(sessionId, status);
      setResultPage(previous => {
        if (!previous) return previous;
        return writePageData(resultsCacheKey(page), {
          ...previous,
          items: previous.items.map(item =>
            item.sessionId === sessionId ? updated : item),
        });
      });
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setUpdatingSessionId(null);
    }
  };

  const toggleCandidate = (groupKey: string) => {
    setExpandedCandidateId(prev => prev === groupKey ? null : groupKey);
  };

  const downloadResume = async (result: HrInterviewResult) => {
    if (!result.resumeId) return;

    setDownloadingSessionId(result.sessionId);
    setError('');
    try {
      const blob = await hrApi.downloadResume(result.sessionId);
      const url = window.URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = result.resumeFilename || `resume-${result.resumeId}`;
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.URL.revokeObjectURL(url);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setDownloadingSessionId(null);
    }
  };

  const toggleVisionEvents = async (sessionId: string) => {
    if (visibleVisionSessionId === sessionId) {
      setVisibleVisionSessionId(null);
      return;
    }
    if (visionEventsBySession[sessionId] && violationConclusionsBySession[sessionId]) {
      setVisibleVisionSessionId(sessionId);
      return;
    }

    setLoadingVisionSessionId(sessionId);
    setError('');
    try {
      const [events, conclusion] = await Promise.all([
        interviewVisionApi.listForAdmin(sessionId),
        interviewVisionApi.getViolationConclusionForAdmin(sessionId),
      ]);
      setVisionEventsBySession(previous => ({...previous, [sessionId]: events}));
      setViolationConclusionsBySession(previous => ({
        ...previous,
        [sessionId]: conclusion,
      }));
      setVisibleVisionSessionId(sessionId);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoadingVisionSessionId(null);
    }
  };

  const toggleVideos = async (sessionId: string) => {
    if (visibleVideoSessionId === sessionId) {
      setVisibleVideoSessionId(null);
      return;
    }
    if (videosBySession[sessionId]) {
      setVisibleVideoSessionId(sessionId);
      return;
    }
    setLoadingVideoSessionId(sessionId);
    setError('');
    try {
      const videos = await interviewVideoApi.listForAdmin(sessionId);
      setVideosBySession(previous => ({...previous, [sessionId]: videos}));
      setVisibleVideoSessionId(sessionId);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoadingVideoSessionId(null);
    }
  };

  const openVideo = async (sessionId: string, event?: InterviewVisionEvent) => {
    const loadingId = `video:${sessionId}`;
    setLoadingEvidenceId(loadingId);
    try {
      let playable = videoCacheRef.current.get(sessionId);
      if (!playable) {
        playable = await interviewVideoApi.getCombinedPlayableForAdmin(sessionId);
        videoCacheRef.current.set(sessionId, playable);
      }
      const offsetMs = event?.videoOffsetMs;
      const clipStartMs = offsetMs == null ? undefined : Math.max(0, offsetMs - 10_000);
      const clipEndMs = offsetMs == null
        ? undefined
        : Math.min(
          playable.durationMs || Number.MAX_SAFE_INTEGER,
          offsetMs + (event?.durationMs || 0) + 10_000,
        );
      setActiveMedia(previous => {
        if (previous?.kind === 'image') previous.revoke();
        return {
          ...playable,
          kind: 'video',
          title: event
            ? `${visionEventSummary(event)}（异常时段前后 10 秒）`
            : '完整面试视频',
          clipStartMs,
          clipEndMs,
        };
      });
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoadingEvidenceId(null);
    }
  };

  const openEvidence = async (sessionId: string, event: InterviewVisionEvent) => {
    const loadingId = `evidence:${event.id}`;
    setLoadingEvidenceId(loadingId);
    try {
      const playable = await interviewVisionApi.getEvidenceForAdmin(sessionId, event.id);
      setActiveMedia(previous => {
        if (previous?.kind === 'image') previous.revoke();
        return {...playable, kind: 'image', title: visionEventSummary(event)};
      });
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoadingEvidenceId(null);
    }
  };

  const toggleEnterpriseReport = async (sessionId: string) => {
    if (visibleReportSessionId === sessionId) {
      setVisibleReportSessionId(null);
      return;
    }
    if (reportsBySession[sessionId]) {
      setVisibleReportSessionId(sessionId);
      return;
    }

    setLoadingReportSessionId(sessionId);
    setError('');
    try {
      const report = await interviewReportApi.getForAdmin(sessionId);
      setReportsBySession(previous => ({...previous, [sessionId]: report}));
      setVisibleReportSessionId(sessionId);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoadingReportSessionId(null);
    }
  };

  useEffect(() => {
    setExpandedCandidateId(null);
    setVisibleVisionSessionId(null);
    setVisibleVideoSessionId(null);
    setVisibleReportSessionId(null);
    void loadResults(page);
  }, [page]);

  useEffect(() => () => {
    videoCacheRef.current.forEach(playable => playable.revoke());
    videoCacheRef.current.clear();
  }, []);

  return (
    <div className="max-w-7xl mx-auto">
      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-2xl font-bold text-slate-800 dark:text-white flex items-center gap-3">
            <BriefcaseBusiness className="w-7 h-7 text-primary-500" />
            正式面试结果
          </h1>
          <p className="text-slate-500 dark:text-slate-400 mt-1">
            按候选人 ID 聚合展示，展开后查看该候选人的所有正式面试记录。
          </p>
        </div>
        <button
          type="button"
          onClick={() => void loadResults(page, true)}
          className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-slate-100 dark:bg-slate-800 text-slate-700 dark:text-slate-200 hover:bg-slate-200 dark:hover:bg-slate-700"
        >
          <RefreshCw className="w-4 h-4" />
          刷新
        </button>
      </div>

      <div className="bg-white dark:bg-slate-800 rounded-xl border border-slate-100 dark:border-slate-700 overflow-hidden">
        {loading ? (
          <div className="flex items-center justify-center py-20">
            <Loader2 className="w-8 h-8 text-primary-500 animate-spin" />
          </div>
        ) : error ? (
          <div className="p-8 text-red-500">{error}</div>
        ) : candidateGroups.length === 0 ? (
          <div className="p-10 text-center">
            <p className="text-slate-600 dark:text-slate-300 font-medium">
              暂无正式面试结果
            </p>
            <p className="text-sm text-slate-400 dark:text-slate-500 mt-2">
              面试者点击“正式面试”并完成后，结果会同步显示在这里。
            </p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[980px]">
              <thead className="bg-slate-50 dark:bg-slate-700/50">
                <tr>
                  <th className="text-left px-5 py-3 text-xs font-semibold text-slate-500 uppercase">
                    候选人
                  </th>
                  <th className="text-left px-5 py-3 text-xs font-semibold text-slate-500 uppercase">
                    面试次数
                  </th>
                  <th className="text-left px-5 py-3 text-xs font-semibold text-slate-500 uppercase">
                    最新方向
                  </th>
                  <th className="text-left px-5 py-3 text-xs font-semibold text-slate-500 uppercase">
                    最新状态
                  </th>
                  <th className="text-left px-5 py-3 text-xs font-semibold text-slate-500 uppercase">
                    最近提交
                  </th>
                  <th className="w-52" />
                </tr>
              </thead>
              <tbody>
                {candidateGroups.map((group, groupIndex) => {
                  const expanded = expandedCandidateId === group.groupKey;
                  return (
                    <Fragment key={group.groupKey}>
                      {(groupIndex === 0
                        || candidateGroups[groupIndex - 1].jobId !== group.jobId
                        || candidateGroups[groupIndex - 1].jobName !== group.jobName) && (
                        <tr className="bg-primary-50/70 dark:bg-primary-950/20">
                          <td colSpan={6} className="px-5 py-2 text-sm font-semibold text-primary-700 dark:text-primary-300">
                            岗位：{group.jobName}
                          </td>
                        </tr>
                      )}
                      <tr
                        key={group.groupKey}
                        className="border-t border-slate-100 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-700/30"
                      >
                        <td className="px-5 py-4">
                          <button
                            type="button"
                            onClick={() => toggleCandidate(group.groupKey)}
                            className="flex items-start gap-3 text-left"
                          >
                            <ChevronDown
                              className={`mt-1 w-4 h-4 text-slate-400 transition-transform ${
                                expanded ? 'rotate-180' : ''
                              }`}
                            />
                            <span>
                              <span className="block font-medium text-slate-800 dark:text-white">
                                {group.candidateName}
                              </span>
                              <span className="block text-xs text-slate-500 dark:text-slate-400">
                                手机号：{group.candidatePhone || '-'}
                              </span>
                              <span className="block text-xs text-slate-400">
                                用户 ID：{group.candidateId}
                              </span>
                            </span>
                          </button>
                        </td>
                        <td className="px-5 py-4 text-sm text-slate-600 dark:text-slate-300">
                          {group.results.length} 场
                        </td>
                        <td className="px-5 py-4 text-sm text-slate-600 dark:text-slate-300">
                          {group.latestResult.skillId} / {group.latestResult.difficulty}
                        </td>
                        <td className="px-5 py-4">
                          <span className={`inline-flex rounded-full px-2.5 py-1 text-xs font-medium ${
                            interviewReviewStatusClass[group.latestResult.status]
                          }`}>
                            {interviewReviewStatusLabel[group.latestResult.status]}
                          </span>
                        </td>
                        <td className="px-5 py-4 text-sm text-slate-500">
                          {formatDateTime(group.latestResult.completedAt || group.latestResult.createdAt)}
                        </td>
                        <td className="px-5 py-4">
                          <div className="flex justify-end gap-3">
                            <button
                              type="button"
                              onClick={() => downloadResume(group.latestResult)}
                              disabled={!group.latestResult.resumeId
                                || downloadingSessionId === group.latestResult.sessionId}
                              className="inline-flex items-center gap-1 text-sm font-medium text-slate-600 hover:text-slate-900 disabled:cursor-not-allowed disabled:text-slate-300 dark:text-slate-300 dark:hover:text-white"
                            >
                              {downloadingSessionId === group.latestResult.sessionId ? (
                                <Loader2 className="w-4 h-4 animate-spin" />
                              ) : (
                                <Download className="w-4 h-4" />
                              )}
                              简历
                            </button>
                            <button
                              type="button"
                              onClick={() => toggleCandidate(group.groupKey)}
                              className="text-sm font-medium text-primary-500 hover:text-primary-600"
                            >
                              {expanded ? '收起' : '展开'}
                            </button>
                          </div>
                        </td>
                      </tr>

                      {expanded && (
                        <tr key={`${group.groupKey}-details`} className="bg-slate-50/70 dark:bg-slate-900/30">
                          <td colSpan={6} className="px-5 py-4">
                            <div className="space-y-3">
                              {group.results.map(result => (
                                <div
                                  key={result.sessionId}
                                  className="rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 overflow-hidden"
                                >
                                  <div className="grid grid-cols-[1.2fr_1fr_0.9fr_1.2fr_auto] items-center gap-4 px-4 py-3">
                                    <div>
                                      <p className="text-sm font-medium text-slate-800 dark:text-white">
                                        {result.resumeFilename || '未绑定简历'}
                                      </p>
                                      <p className="text-xs text-slate-400">
                                        简历 ID：{result.resumeId ?? '-'} / 会话：#{result.sessionId.slice(-8)}
                                      </p>
                                    </div>
                                    <p className="text-sm text-slate-600 dark:text-slate-300">
                                      {result.skillId} / {result.difficulty}
                                    </p>
                                    <div>
                                      <span className={`inline-flex rounded-full px-2.5 py-1 text-xs font-medium ${
                                        interviewReviewStatusClass[result.status]
                                      }`}>
                                        {interviewReviewStatusLabel[result.status]}
                                      </span>
                                      <p className="text-xs text-slate-400">
                                        {formatDateTime(result.completedAt || result.createdAt)}
                                      </p>
                                    </div>
                                    <div className="flex items-center gap-2">
                                      {result.status === 'UNDER_MANUAL_REVIEW' && (
                                        <>
                                          <button
                                            type="button"
                                            disabled={updatingSessionId === result.sessionId}
                                            onClick={() => updateReviewStatus(result.sessionId, 'PASSED')}
                                            className="rounded-lg bg-emerald-50 px-3 py-1.5 text-xs font-medium text-emerald-700 disabled:opacity-60"
                                          >
                                            通过
                                          </button>
                                          <button
                                            type="button"
                                            disabled={updatingSessionId === result.sessionId}
                                            onClick={() => updateReviewStatus(result.sessionId, 'REJECTED')}
                                            className="rounded-lg bg-red-50 px-3 py-1.5 text-xs font-medium text-red-600 disabled:opacity-60"
                                          >
                                            不通过
                                          </button>
                                        </>
                                      )}
                                      {(result.status === 'PASSED' || result.status === 'REJECTED') && (
                                        <button
                                          type="button"
                                          disabled={updatingSessionId === result.sessionId}
                                          onClick={() => updateReviewStatus(
                                            result.sessionId, 'UNDER_MANUAL_REVIEW')}
                                          className="rounded-lg bg-slate-100 px-3 py-1.5 text-xs font-medium text-slate-600 disabled:opacity-60"
                                        >
                                          重新审核
                                        </button>
                                      )}
                                    </div>
                                    <div className="flex justify-end gap-3">
                                      <button
                                        type="button"
                                        onClick={() => toggleEnterpriseReport(result.sessionId)}
                                        disabled={loadingReportSessionId === result.sessionId}
                                        className="inline-flex items-center gap-1 text-sm font-medium text-emerald-600 hover:text-emerald-700 disabled:opacity-60"
                                      >
                                        {loadingReportSessionId === result.sessionId && (
                                          <Loader2 className="w-4 h-4 animate-spin" />
                                        )}
                                        企业报告
                                      </button>
                                      <button
                                        type="button"
                                        onClick={() => toggleVideos(result.sessionId)}
                                        disabled={loadingVideoSessionId === result.sessionId}
                                        className="inline-flex items-center gap-1 text-sm font-medium text-sky-600 hover:text-sky-700 disabled:opacity-60"
                                      >
                                        {loadingVideoSessionId === result.sessionId ? (
                                          <Loader2 className="w-4 h-4 animate-spin" />
                                        ) : (
                                          <Video className="w-4 h-4" />
                                        )}
                                        面试视频
                                      </button>
                                      <button
                                        type="button"
                                        onClick={() => toggleVisionEvents(result.sessionId)}
                                        disabled={loadingVisionSessionId === result.sessionId}
                                        className="inline-flex items-center gap-1 text-sm font-medium text-amber-600 hover:text-amber-700 disabled:opacity-60"
                                      >
                                        {loadingVisionSessionId === result.sessionId ? (
                                          <Loader2 className="w-4 h-4 animate-spin" />
                                        ) : (
                                          <Eye className="w-4 h-4" />
                                        )}
                                        过程事件
                                      </button>
                                      <button
                                        type="button"
                                        onClick={() => navigate(`/interviews/${result.sessionId}`)}
                                        className="text-sm font-medium text-primary-500 hover:text-primary-600"
                                      >
                                        查看结果
                                      </button>
                                    </div>
                                  </div>

                                  {visibleReportSessionId === result.sessionId
                                    && reportsBySession[result.sessionId] && (
                                    <div className="border-t border-slate-100 dark:border-slate-700 bg-emerald-50/50 dark:bg-emerald-950/20 px-4 py-4">
                                      <ViolationConclusionCard
                                        conclusion={reportsBySession[
                                          result.sessionId
                                        ].violationConclusion}
                                      />
                                      <p className="mt-3 text-sm text-slate-700 dark:text-slate-200">
                                        {reportsBySession[result.sessionId].summary}
                                      </p>
                                      <p className="mt-2 text-xs text-slate-500">
                                        建议：{reportsBySession[result.sessionId].recommendation}
                                      </p>
                                      <p className="mt-2 text-xs text-amber-700 dark:text-amber-300">
                                        报告用于辅助人工复核，不代表自动录用决定。
                                      </p>
                                    </div>
                                  )}

                                  {visibleVideoSessionId === result.sessionId && (
                                    <div className="border-t border-slate-100 bg-sky-50/60 px-4 py-3 dark:border-slate-700 dark:bg-sky-950/20">
                                      {(videosBySession[result.sessionId] ?? []).length === 0 ? (
                                        <p className="text-sm text-slate-500">尚未上传面试视频</p>
                                      ) : (
                                        <div className="flex items-center justify-between rounded-lg border border-sky-100 bg-white px-4 py-3 dark:border-sky-900 dark:bg-slate-800">
                                          <div>
                                            <p className="text-sm font-medium text-slate-700 dark:text-slate-200">
                                              完整面试视频
                                            </p>
                                            <p className="mt-1 text-xs text-slate-400">
                                              {videosBySession[result.sessionId].length} 个分片，约 {Math.ceil(
                                                videosBySession[result.sessionId]
                                                  .reduce((total, video) => total + video.durationMs, 0) / 1000,
                                              )} 秒
                                            </p>
                                          </div>
                                          <button
                                            type="button"
                                            disabled={videosBySession[result.sessionId]
                                              .some(video => video.status !== 'UPLOADED')
                                              || loadingEvidenceId === `video:${result.sessionId}`}
                                            onClick={() => void openVideo(result.sessionId)}
                                            className="rounded-lg bg-sky-600 px-4 py-2 text-sm font-medium text-white hover:bg-sky-700 disabled:opacity-50"
                                          >
                                            {loadingEvidenceId === `video:${result.sessionId}`
                                              ? '正在加载…'
                                              : '播放完整视频'}
                                          </button>
                                        </div>
                                      )}
                                    </div>
                                  )}

                                  {visibleVisionSessionId === result.sessionId && (
                                    <div className="border-t border-slate-100 dark:border-slate-700 bg-amber-50/60 dark:bg-amber-950/20 px-4 py-3">
                                      {violationConclusionsBySession[result.sessionId] && (
                                        <ViolationConclusionCard
                                          conclusion={violationConclusionsBySession[result.sessionId]}
                                        />
                                      )}
                                      <p className="mb-2 mt-3 text-xs text-amber-800 dark:text-amber-300">
                                        违规结论由切屏次数、异常时段数量及持续时间规则计算，
                                        最终录用决定仍需管理员人工复核。
                                      </p>
                                      {(visionEventsBySession[result.sessionId] ?? []).length === 0 ? (
                                        <p className="text-sm text-slate-500">未记录异常画面事件</p>
                                      ) : (
                                        <div className="space-y-2">
                                          {visionEventsBySession[result.sessionId].map(event => (
                                            <div
                                              key={event.id}
                                              className="flex items-center justify-between gap-4 text-sm"
                                            >
                                              <span className="font-medium text-slate-700 dark:text-slate-200">
                                                {visionEventSummary(event)}
                                              </span>
                                              <span className="text-xs text-slate-500">
                                                {formatDateTime(event.occurredAt)}
                                                {event.durationMs != null
                                                  ? ` · 持续 ${formatVisionDuration(event.durationMs)}`
                                                  : ''}
                                                {!event.episodeClosed ? ' · 检测中' : ''}
                                              </span>
                                              {event.eventType !== 'SCREEN_CAPTURED'
                                                && event.videoOffsetMs != null && (
                                                <button
                                                  type="button"
                                                  disabled={loadingEvidenceId
                                                    === `video:${result.sessionId}`}
                                                  onClick={() => void openVideo(result.sessionId, event)}
                                                  className="text-xs font-medium text-sky-700 hover:text-sky-800 disabled:opacity-50"
                                                >
                                                  查看前后 10 秒
                                                </button>
                                              )}
                                              {event.evidenceAvailable && (
                                                <button
                                                  type="button"
                                                  disabled={loadingEvidenceId === `evidence:${event.id}`}
                                                  onClick={() => void openEvidence(result.sessionId, event)}
                                                  className="text-xs font-medium text-amber-700 hover:text-amber-800 disabled:opacity-50"
                                                >
                                                  查看截图
                                                </button>
                                              )}
                                            </div>
                                          ))}
                                        </div>
                                      )}
                                    </div>
                                  )}
                                </div>
                              ))}
                            </div>
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
      {resultPage && resultPage.totalElements > 0 && (
        <div className="mt-4 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-slate-100 bg-white px-5 py-3 text-sm dark:border-slate-700 dark:bg-slate-800">
          <span className="text-slate-500 dark:text-slate-400">
            共 {resultPage.totalElements} 条记录，每页 {resultPage.size} 条
          </span>
          <div className="flex items-center gap-3">
            <button
              type="button"
              disabled={loading || page === 0}
              onClick={() => setPage(current => Math.max(0, current - 1))}
              className="rounded-lg border border-slate-200 px-3 py-1.5 text-slate-600 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-40 dark:border-slate-600 dark:text-slate-300 dark:hover:bg-slate-700"
            >
              上一页
            </button>
            <span className="min-w-24 text-center text-slate-600 dark:text-slate-300">
              第 {resultPage.page + 1} / {Math.max(resultPage.totalPages, 1)} 页
            </span>
            <button
              type="button"
              disabled={loading || page >= resultPage.totalPages - 1}
              onClick={() => setPage(current => current + 1)}
              className="rounded-lg border border-slate-200 px-3 py-1.5 text-slate-600 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-40 dark:border-slate-600 dark:text-slate-300 dark:hover:bg-slate-700"
            >
              下一页
            </button>
          </div>
        </div>
      )}
      {activeMedia && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/80 p-4">
          <div className="w-full max-w-5xl rounded-2xl bg-white p-4 shadow-2xl dark:bg-slate-900">
            <div className="mb-3 flex items-center justify-between">
              <h3 className="font-semibold text-slate-800 dark:text-white">{activeMedia.title}</h3>
              <button
                type="button"
                onClick={() => {
                  if (activeMedia.kind === 'image') activeMedia.revoke();
                  setActiveMedia(null);
                }}
                className="rounded-lg p-2 text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-800"
              >
                <X className="h-5 w-5" />
              </button>
            </div>
            {activeMedia.kind === 'video' ? (
              <video
                className="max-h-[75vh] w-full rounded-xl bg-black"
                controls
                src={activeMedia.url}
                onLoadedMetadata={event => {
                  const video = event.currentTarget;
                  const seekToClipStart = () => {
                    video.currentTime = (activeMedia.clipStartMs || 0) / 1000;
                  };
                  if (!Number.isFinite(video.duration)) {
                    video.currentTime = Number.MAX_SAFE_INTEGER;
                    video.ontimeupdate = () => {
                      video.ontimeupdate = null;
                      seekToClipStart();
                    };
                  } else {
                    seekToClipStart();
                  }
                }}
                onTimeUpdate={event => {
                  const video = event.currentTarget;
                  if (activeMedia.clipEndMs != null
                      && Number.isFinite(video.duration)
                      && video.currentTime * 1000 >= activeMedia.clipEndMs) {
                    video.pause();
                  }
                }}
              />
            ) : (
              <img className="max-h-[75vh] w-full rounded-xl object-contain" src={activeMedia.url} alt={activeMedia.title} />
            )}
          </div>
        </div>
      )}
    </div>
  );
}
