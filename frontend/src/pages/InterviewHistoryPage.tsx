import {Fragment, useCallback, useEffect, useRef, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {AnimatePresence, motion} from 'framer-motion';
import {historyApi} from '../api/history';
import {interviewApi, type TextSessionMeta} from '../api/interview';
import {formatDate} from '../utils/date';
import type {InterviewReviewStatus} from '../api/history';
import {
  interviewReviewStatusLabel,
} from '../utils/interviewReviewStatus';
import {skillApi, type SkillDTO} from '../api/skill';
import {getTemplateName} from '../utils/voiceInterview';
import DeleteConfirmDialog from '../components/DeleteConfirmDialog';
import {
  AlertCircle,
  CheckCircle,
  ChevronRight,
  Clock,
  Download,
  FileText,
  Loader2,
  Mic,
  PlayCircle,
  RefreshCw,
  Search,
  Trash2,
  Users,
} from 'lucide-react';

type InterviewType = 'all' | 'text' | 'voice';

interface UnifiedInterviewItem {
  id: string;
  type: 'text' | 'voice';
  title: string;
  sessionId: string;
  status: InterviewReviewStatus;
  executionStatus: string;
  totalQuestions?: number;
  actualDuration?: number;
  createdAt: string;
  resumeId?: number;
  voiceSessionId?: number;
  jobId?: number;
  jobName: string;
}

interface InterviewStats {
  totalCount: number;
  completedCount: number;
  passedCount: number;
}

function isCompletedStatus(status: InterviewReviewStatus): boolean {
  return status !== 'INCOMPLETE';
}

function isLiveStatus(status: string): boolean {
  return status === 'IN_PROGRESS' || status === 'PAUSED';
}

function StatusIcon({ item }: { item: UnifiedInterviewItem }) {
  if (item.status === 'REJECTED') return <AlertCircle className="w-4 h-4 text-red-500 dark:text-red-400"/>;
  if (item.status === 'UNDER_MANUAL_REVIEW') return <RefreshCw className="w-4 h-4 text-amber-500 dark:text-amber-400"/>;
  if (item.status === 'PASSED') {
    return <CheckCircle className="w-4 h-4 text-green-500 dark:text-green-400"/>;
  }
  if (item.executionStatus === 'IN_PROGRESS') return <PlayCircle className="w-4 h-4 text-blue-500 dark:text-blue-400"/>;
  return <Clock className="w-4 h-4 text-yellow-500 dark:text-yellow-400"/>;
}

function getStatusText(item: UnifiedInterviewItem): string {
  return interviewReviewStatusLabel[item.status];
}

function formatDuration(seconds?: number): string {
  if (!seconds) return '-';
  const mins = Math.floor(seconds / 60);
  const secs = seconds % 60;
  return `${mins}分${secs}秒`;
}

function StatCard({
  icon: Icon,
  label,
  value,
  suffix,
  color,
}: {
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  value: number | string;
  suffix?: string;
  color: string;
}) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      className="bg-white dark:bg-slate-800 rounded-xl p-6 shadow-sm border border-slate-100 dark:border-slate-700"
    >
      <div className="flex items-center gap-4">
        <div className={`p-3 rounded-lg ${color}`}>
          <Icon className="w-6 h-6 text-white" />
        </div>
        <div>
          <p className="text-sm text-slate-500 dark:text-slate-400">{label}</p>
          <p className="text-2xl font-bold text-slate-800 dark:text-white">
            {value}{suffix && <span className="text-base font-normal text-slate-400 dark:text-slate-500 ml-1">{suffix}</span>}
          </p>
        </div>
      </div>
    </motion.div>
  );
}

function TypeBadge({ type }: { type: 'text' | 'voice' }) {
  if (type === 'voice') {
    return (
      <span className="inline-flex items-center gap-1 px-2 py-0.5 bg-purple-100 dark:bg-purple-900/30 text-purple-700 dark:text-purple-400 rounded-full text-xs font-medium">
        <Mic className="w-3 h-3" />
        语音
      </span>
    );
  }
  return (
    <span className="inline-flex items-center gap-1 px-2 py-0.5 bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-400 rounded-full text-xs font-medium">
      <FileText className="w-3 h-3" />
      文字
    </span>
  );
}

interface InterviewHistoryPageProps {
  onBack: () => void;
  onViewInterview: (sessionId: string, resumeId?: number) => void;
  onContinueInterview?: (sessionId: string, jobId: number) => void;
}

/** Shallow comparison for polling change-detection */
function itemsEqual(a: UnifiedInterviewItem[], b: UnifiedInterviewItem[]): boolean {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) {
    const ai = a[i], bi = b[i];
    if (ai.id !== bi.id || ai.status !== bi.status
        || ai.executionStatus !== bi.executionStatus) return false;
  }
  return true;
}

export default function InterviewHistoryPage({ onBack: _onBack, onViewInterview, onContinueInterview }: InterviewHistoryPageProps) {
  const navigate = useNavigate();
  const [items, setItems] = useState<UnifiedInterviewItem[]>([]);
  const [stats, setStats] = useState<InterviewStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [searchTerm, setSearchTerm] = useState('');
  const [typeFilter, setTypeFilter] = useState<InterviewType>('all');
  const [deletingSessionId, setDeletingSessionId] = useState<string | null>(null);
  const [deleteItem, setDeleteItem] = useState<UnifiedInterviewItem | null>(null);
  const [exporting, setExporting] = useState<string | null>(null);
  const skillsRef = useRef<SkillDTO[]>([]);
  const skillsLoadedRef = useRef(false);

  const loadAll = useCallback(async (isPolling = false) => {
    if (!isPolling) setLoading(true);

    try {
      // Only fetch skills on first load; reuse cached ref on polling
      if (!skillsLoadedRef.current) {
        skillsRef.current = await skillApi.listSkills().catch(() => [] as SkillDTO[]);
        skillsLoadedRef.current = true;
      }
      const loadedSkills = skillsRef.current;
      const all = await loadTextInterviews(loadedSkills);
      all.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());

      setItems(prev => {
        if (isPolling && itemsEqual(prev, all)) return prev;
        return all;
      });

      // Compute stats
      const completed = all.filter(i => isCompletedStatus(i.status));
      const newStats = {
        totalCount: all.length,
        completedCount: completed.length,
        passedCount: all.filter(item => item.status === 'PASSED').length,
      };
      setStats(prev => {
        if (isPolling && prev &&
            prev.totalCount === newStats.totalCount &&
            prev.completedCount === newStats.completedCount &&
            prev.passedCount === newStats.passedCount) return prev;
        return newStats;
      });
    } catch (err) {
      console.error('加载面试记录失败', err);
    } finally {
      if (!isPolling) setLoading(false);
    }
  }, []);

  // Load text interviews from dedicated API
  async function loadTextInterviews(skills: SkillDTO[]): Promise<UnifiedInterviewItem[]> {
    try {
      const sessions = await interviewApi.listSessions();
      return sessions.map((session: TextSessionMeta) => ({
        id: session.sessionId,
        type: 'text' as const,
        title: session.jobName || getTemplateName(session.skillId, skills),
        sessionId: session.sessionId,
        status: session.status,
        executionStatus: session.executionStatus,
        totalQuestions: session.totalQuestions,
        createdAt: session.createdAt,
        resumeId: session.resumeId ?? undefined,
        jobId: session.jobId ?? undefined,
        jobName: session.jobName || '历史面试',
      }));
    } catch {
      return [];
    }
  }

  useEffect(() => {
    loadAll();
  }, [loadAll]);

  const handleRowClick = (item: UnifiedInterviewItem) => {
    if (item.type === 'text') {
      onViewInterview(item.sessionId, item.resumeId);
    } else if (item.voiceSessionId) {
      const isLive = isLiveStatus(item.executionStatus);
      if (isLive) {
        navigate('/voice-interview', { state: { voiceSessionId: item.voiceSessionId } });
      } else {
        navigate(`/voice-interview/${item.voiceSessionId}/evaluation`);
      }
    }
  };

  const handleDeleteClick = (item: UnifiedInterviewItem, e: React.MouseEvent) => {
    e.stopPropagation();
    setDeleteItem(item);
  };

  const handleDeleteConfirm = async () => {
    if (!deleteItem) return;
    setDeletingSessionId(deleteItem.sessionId);
    try {
      await historyApi.deleteInterview(deleteItem.sessionId);
      await loadAll();
      setDeleteItem(null);
    } catch (err) {
      alert(err instanceof Error ? err.message : '删除失败，请稍后重试');
    } finally {
      setDeletingSessionId(null);
    }
  };

  const handleExport = async (sessionId: string, e: React.MouseEvent) => {
    e.stopPropagation();
    setExporting(sessionId);
    try {
      const blob = await historyApi.exportInterviewPdf(sessionId);
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `面试报告_${sessionId.slice(-8)}.pdf`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      window.URL.revokeObjectURL(url);
    } catch {
      alert('导出失败，请重试');
    } finally {
      setExporting(null);
    }
  };

  // Filter + search
  const filtered = items
    .filter(item => {
      if (typeFilter !== 'all' && item.type !== typeFilter) return false;
      if (searchTerm && !item.title.toLowerCase().includes(searchTerm.toLowerCase())) return false;
      return true;
    })
    .sort((a, b) => a.jobName.localeCompare(b.jobName, 'zh-CN')
      || new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());

  return (
    <motion.div className="w-full" initial={{ opacity: 0 }} animate={{ opacity: 1 }}>
      {/* Header */}
      <div className="flex justify-between items-start mb-8 flex-wrap gap-6">
        <div>
          <motion.h1
            className="text-2xl font-bold text-slate-800 dark:text-white flex items-center gap-3"
            initial={{ opacity: 0, x: -20 }}
            animate={{ opacity: 1, x: 0 }}
          >
            <Users className="w-7 h-7 text-primary-500" />
            面试记录
          </motion.h1>
          <motion.p
            className="text-slate-500 dark:text-slate-400 mt-1"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={{ delay: 0.1 }}
          >
            查看和管理所有模拟面试记录
          </motion.p>
        </div>

        <motion.div
          className="flex items-center gap-3 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-600 rounded-xl px-4 py-2.5 min-w-[280px] focus-within:border-primary-500 focus-within:ring-2 focus-within:ring-primary-100 dark:focus-within:ring-primary-900/30 transition-all"
          initial={{ opacity: 0, x: 20 }}
          animate={{ opacity: 1, x: 0 }}
        >
          <Search className="w-5 h-5 text-slate-400" />
          <input
            type="text"
            placeholder="搜索名称..."
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            className="flex-1 outline-none text-slate-700 dark:text-slate-200 placeholder:text-slate-400 bg-transparent"
          />
        </motion.div>
      </div>

      {/* Stats */}
      {stats && (
        <div className="grid grid-cols-1 md:grid-cols-3 gap-6 mb-8">
          <StatCard icon={Users} label="面试总数" value={stats.totalCount} color="bg-primary-500" />
          <StatCard icon={CheckCircle} label="已完成" value={stats.completedCount} color="bg-emerald-500" />
          <StatCard icon={CheckCircle} label="已通过" value={stats.passedCount} color="bg-indigo-500" />
        </div>
      )}

      {/* Type filter tabs */}
      <div className="flex items-center gap-2 mb-6">
        {([
          { key: 'all', label: '全部' },
          { key: 'text', label: '正式面试' },
        ] as const).map(tab => (
          <button
            key={tab.key}
            onClick={() => setTypeFilter(tab.key)}
            className={`px-4 py-2 rounded-lg text-sm font-medium transition-colors ${
              typeFilter === tab.key
                ? 'bg-primary-500 text-white'
                : 'bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 border border-slate-200 dark:border-slate-600'
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* Loading */}
      {loading && (
        <div className="flex items-center justify-center py-20">
          <Loader2 className="w-8 h-8 text-primary-500 animate-spin" />
        </div>
      )}

      {/* Empty */}
      {!loading && filtered.length === 0 && (
        <motion.div
          className="text-center py-20 bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-100 dark:border-slate-700"
          initial={{ opacity: 0, scale: 0.95 }}
          animate={{ opacity: 1, scale: 1 }}
        >
          <Users className="w-16 h-16 text-slate-300 dark:text-slate-600 mx-auto mb-4" />
          <h3 className="text-xl font-semibold text-slate-700 dark:text-slate-300 mb-2">暂无面试记录</h3>
          <p className="text-slate-500 dark:text-slate-400">开始一次模拟面试后，记录将显示在这里</p>
        </motion.div>
      )}

      {/* Table */}
      {!loading && filtered.length > 0 && (
        <motion.div
          className="bg-white dark:bg-slate-800 rounded-xl shadow-sm border border-slate-100 dark:border-slate-700 overflow-hidden"
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.2 }}
        >
          <table className="w-full">
            <thead className="bg-slate-50 dark:bg-slate-700/50 border-b border-slate-100 dark:border-slate-600">
              <tr>
                <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">类型</th>
                <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">名称</th>
                <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">状态</th>
                <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">详情</th>
                <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">时间</th>
                <th className="text-right px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">操作</th>
              </tr>
            </thead>
            <tbody>
              <AnimatePresence>
                {filtered.map((item, index) => (
                  <Fragment key={item.id}>
                  {(index === 0
                    || filtered[index - 1].jobId !== item.jobId
                    || filtered[index - 1].jobName !== item.jobName) && (
                    <tr className="bg-primary-50/70 dark:bg-primary-950/20">
                      <td colSpan={6} className="px-6 py-2 text-sm font-semibold text-primary-700 dark:text-primary-300">
                        岗位：{item.jobName}
                      </td>
                    </tr>
                  )}
                  <motion.tr
                    key={`${item.id}-row`}
                    initial={{ opacity: 0, y: 10 }}
                    animate={{ opacity: 1, y: 0 }}
                    transition={{ delay: index * 0.05 }}
                    onClick={() => handleRowClick(item)}
                    className="border-b border-slate-50 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-700/50 cursor-pointer transition-colors group"
                  >
                    <td className="px-6 py-4">
                      <TypeBadge type={item.type} />
                    </td>
                    <td className="px-6 py-4">
                      <div className="flex items-center gap-3">
                        {item.type === 'text' ? (
                          <FileText className="w-5 h-5 text-slate-400" />
                        ) : (
                          <Mic className="w-5 h-5 text-purple-400" />
                        )}
                        <div>
                          <p className="font-medium text-slate-800 dark:text-white">{item.title}</p>
                          <p className="text-xs text-slate-400 dark:text-slate-500">#{item.id.slice(-8)}</p>
                        </div>
                      </div>
                    </td>
                    <td className="px-6 py-4">
                      <div className="flex items-center gap-2">
                        <StatusIcon item={item} />
                        <span className="text-sm text-slate-600 dark:text-slate-300">{getStatusText(item)}</span>
                      </div>
                    </td>
                    <td className="px-6 py-4">
                      {item.type === 'text' && item.totalQuestions != null ? (
                        <span className="inline-flex items-center gap-1.5 px-2.5 py-1 bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300 rounded-lg text-sm">
                          {item.totalQuestions} 题
                        </span>
                      ) : item.type === 'voice' ? (
                        <span className="text-sm text-slate-500 dark:text-slate-400">
                          {formatDuration(item.actualDuration)}
                        </span>
                      ) : (
                        <span className="text-slate-400">-</span>
                      )}
                    </td>
                    <td className="px-6 py-4 text-sm text-slate-500 dark:text-slate-400">
                      {formatDate(item.createdAt)}
                    </td>
                    <td className="px-6 py-4 text-right">
                      <div className="flex items-center justify-end gap-1">
                        {item.type === 'text'
                          && Boolean(item.jobId)
                          && item.executionStatus !== 'COMPLETED'
                          && item.executionStatus !== 'EVALUATED'
                          && onContinueInterview && (
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              onContinueInterview(item.sessionId, item.jobId!);
                            }}
                            className="p-2 text-slate-400 hover:text-blue-500 hover:bg-blue-50 dark:hover:bg-blue-900/30 rounded-lg transition-colors"
                            title="继续面试"
                          >
                            <PlayCircle className="w-4 h-4" />
                          </button>
                        )}
                        {item.type === 'voice'
                          && isLiveStatus(item.executionStatus)
                          && item.voiceSessionId && (
                          <button
                            onClick={(e) => { e.stopPropagation(); navigate('/voice-interview', { state: { voiceSessionId: item.voiceSessionId } }); }}
                            className="p-2 text-slate-400 hover:text-blue-500 hover:bg-blue-50 dark:hover:bg-blue-900/30 rounded-lg transition-colors"
                            title="继续面试"
                          >
                            <PlayCircle className="w-4 h-4" />
                          </button>
                        )}
                        {isCompletedStatus(item.status) && item.type === 'text' && (
                          <button
                            onClick={(e) => handleExport(item.sessionId, e)}
                            disabled={exporting === item.sessionId}
                            className="p-2 text-slate-400 hover:text-primary-500 hover:bg-primary-50 dark:hover:bg-primary-900/30 rounded-lg transition-colors disabled:opacity-50"
                            title="导出PDF"
                          >
                            {exporting === item.sessionId ? (
                              <Loader2 className="w-4 h-4 animate-spin" />
                            ) : (
                              <Download className="w-4 h-4" />
                            )}
                          </button>
                        )}
                        {(item.type === 'voice' || !item.jobId) && <button
                            onClick={(e) => handleDeleteClick(item, e)}
                            disabled={deletingSessionId === item.sessionId}
                            className="p-2 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/30 rounded-lg transition-colors disabled:opacity-50"
                            title="删除"
                          >
                            <Trash2 className="w-4 h-4" />
                          </button>}
                        <ChevronRight className="w-5 h-5 text-slate-300 dark:text-slate-600 group-hover:text-primary-500 group-hover:translate-x-1 transition-all"/>
                      </div>
                    </td>
                  </motion.tr>
                  </Fragment>
                ))}
              </AnimatePresence>
            </tbody>
          </table>
        </motion.div>
      )}

      <DeleteConfirmDialog
        open={deleteItem !== null}
        item={deleteItem ? { id: 0, sessionId: deleteItem.sessionId } : null}
        itemType="面试记录"
        loading={deletingSessionId !== null}
        onConfirm={handleDeleteConfirm}
        onCancel={() => setDeleteItem(null)}
      />
    </motion.div>
  );
}
