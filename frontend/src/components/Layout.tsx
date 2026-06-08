import {Link, Outlet, useLocation, useNavigate} from 'react-router-dom';
import {motion} from 'framer-motion';
import {BriefcaseBusiness, ChevronRight, ClipboardList, Database, FileStack, LogOut, MessageSquare, Moon, Settings, Sparkles, Sun, UserCog, Users,} from 'lucide-react';
import {useTheme} from '../hooks/useTheme';
import {Suspense, useEffect, useState} from 'react';
import { authApi, type CurrentUser } from '../api/auth';
import { clearAuth, getRefreshToken, getStoredUser } from '../utils/authStorage';

interface NavItem {
  id: string;
  path: string;
  label: string;
  icon: React.ComponentType<{ className?: string }>;
  description?: string;
}

interface NavGroup {
  id: string;
  title: string;
  items: NavItem[];
}

export default function Layout() {
  const location = useLocation();
  const currentPath = location.pathname;
  const {theme, toggleTheme} = useTheme();
  const navigate = useNavigate();
  const [currentUser, setCurrentUser] = useState<CurrentUser | null>(() => getStoredUser());
  const [fullscreen, setFullscreen] = useState(Boolean(document.fullscreenElement));

  useEffect(() => {
    const updateUser = () => setCurrentUser(getStoredUser());
    window.addEventListener('auth:changed', updateUser);
    return () => window.removeEventListener('auth:changed', updateUser);
  }, []);

  useEffect(() => {
    const updateFullscreen = () => setFullscreen(Boolean(document.fullscreenElement));
    document.addEventListener('fullscreenchange', updateFullscreen);
    return () => document.removeEventListener('fullscreenchange', updateFullscreen);
  }, []);

  const handleLogout = async () => {
    const refreshToken = getRefreshToken();
    clearAuth();
    navigate('/login', { replace: true });
    try {
      await authApi.logout(refreshToken || undefined);
    } catch {
      // local logout has already completed
    }
  };

  // 按业务模块组织的导航项
  const navGroups: NavGroup[] = [
    ...(currentUser?.roles.includes('ADMIN') ? [{
      id: 'hr',
      title: '企业招聘',
      items: [
        {
          id: 'hr-results',
          path: '/hr/interview-results',
          label: '正式面试结果',
          icon: BriefcaseBusiness,
          description: '查看候选人 AI 面试结果',
        },
        {
          id: 'admin-users',
          path: '/admin/users',
          label: '用户与权限',
          icon: UserCog,
          description: '启用、禁用或锁定用户账号',
        },
        {
          id: 'admin-recruitment',
          path: '/admin/recruitment',
          label: '岗位与面试任务',
          icon: ClipboardList,
          description: '维护岗位并向候选人分配任务',
        },
      ],
    }] : []),
    {
      id: 'interview',
      title: '面试准备',
      items: [
        { id: 'resumes', path: '/history', label: '简历管理', icon: FileStack, description: '管理简历，AI 分析' },
        { id: 'jobs', path: '/jobs', label: '招聘岗位', icon: BriefcaseBusiness, description: '选择岗位参加 AI 面试' },
        { id: 'interviews', path: '/interviews', label: '面试记录', icon: Users, description: '查看面试历史' },
      ],
    },
    {
      id: 'knowledge',
      title: '知识库',
      items: [
        { id: 'kb-manage', path: '/knowledgebase', label: '知识库管理', icon: Database, description: '管理知识文档' },
        { id: 'chat', path: '/knowledgebase/chat', label: '问答助手', icon: MessageSquare, description: '基于知识库问答' },
      ],
    },
    {
      id: 'system',
      title: '系统',
      items: [
        { id: 'settings', path: '/settings', label: '设置', icon: Settings, description: '管理模型和语音服务' },
      ],
    },
  ];

  // 判断当前页面是否匹配导航项
  const isActive = (path: string) => {
    if (path.startsWith('#')) return false;
    if (path === '/history') {
      return currentPath === '/history'
        || currentPath === '/'
        || currentPath.startsWith('/history/')
        || currentPath === '/upload';
    }
    if (path === '/knowledgebase') {
      return currentPath === '/knowledgebase' || currentPath === '/knowledgebase/upload';
    }
    return currentPath.startsWith(path);
  };

  const isAdmin = currentUser?.roles.includes('ADMIN');
  const isAdminOrHr = currentUser?.roles.some(role => role === 'ADMIN' || role === 'HR');
  const isFullscreenInterview = fullscreen && currentPath === '/interview';

  useEffect(() => {
    if (!isAdmin) return;
    const timerId = window.setTimeout(() => {
      void Promise.allSettled([
        import('../pages/AdminUsersPage').then(module => module.preloadAdminUsersPage()),
        import('../pages/AdminRecruitmentPage')
          .then(module => module.preloadAdminRecruitmentPage()),
        import('../pages/HrInterviewResultsPage')
          .then(module => module.preloadHrInterviewResultsPage()),
      ]);
    }, 300);
    return () => window.clearTimeout(timerId);
  }, [isAdmin]);

  const visibleNavGroups = navGroups
    .map(group => ({
      ...group,
      items: group.items.filter(item => isAdminOrHr
        ? group.id !== 'interview'
        : ['resumes', 'jobs', 'interviews'].includes(item.id)),
    }))
    .filter(group => group.items.length > 0);

  return (
    <div className="flex min-h-screen bg-gradient-to-br from-slate-50 to-indigo-50 dark:from-slate-900 dark:to-slate-800">
      {/* 左侧边栏 */}
      <aside className={`${isFullscreenInterview ? 'hidden' : 'flex'} w-64 bg-white dark:bg-slate-900 border-r border-slate-100 dark:border-slate-700 fixed h-screen left-0 top-0 z-50 flex-col`}>
        {/* Logo */}
        <div className="p-6 border-b border-slate-100 dark:border-slate-700 flex items-center justify-between">
          <Link to="/history" className="flex items-center gap-3">
            <div className="w-10 h-10 bg-gradient-to-br from-primary-500 to-primary-600 rounded-xl flex items-center justify-center text-white shadow-lg shadow-primary-500/30">
              <Sparkles className="w-5 h-5" />
            </div>
            <div>
              <span className="text-lg font-bold text-slate-800 dark:text-white tracking-tight block">AI Interview</span>
              <span className="text-xs text-slate-400 dark:text-slate-500">智能面试助手</span>
            </div>
          </Link>
        </div>

        {/* 主题切换按钮 */}
        <div className="px-4 pb-2">
          <button
            onClick={toggleTheme}
            className="w-full flex items-center justify-center gap-2 px-3 py-2 rounded-lg bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-300 hover:bg-slate-200 dark:hover:bg-slate-700 transition-colors"
          >
            {theme === 'dark' ? (
              <>
                <Sun className="w-4 h-4" />
                <span className="text-sm font-medium">浅色模式</span>
              </>
            ) : (
              <>
                <Moon className="w-4 h-4" />
                <span className="text-sm font-medium">深色模式</span>
              </>
            )}
          </button>
        </div>

        {/* 导航菜单 */}
        <nav className="flex-1 p-4 overflow-y-auto">
          <div className="space-y-6">
            {visibleNavGroups.map((group) => (
              <div key={group.id}>
                <div className="px-3 mb-2">
                  <span className="text-xs font-semibold text-slate-400 dark:text-slate-500 uppercase tracking-wider">
                    {group.title}
                  </span>
                </div>
                <div className="space-y-1">
                  {group.items.map((item) => {
                    const active = isActive(item.path);

                    return (
                      <Link
                        key={item.id}
                        to={item.path}
                        className={`group relative flex items-center gap-3 px-3 py-2.5 rounded-xl transition-all duration-200
                          ${active
                            ? 'bg-primary-50 dark:bg-primary-900/30 text-primary-600 dark:text-primary-400'
                            : 'text-slate-600 dark:text-slate-400 hover:bg-slate-50 dark:hover:bg-slate-800 hover:text-slate-900 dark:hover:text-white'
                          }`}
                      >
                        <div className={`w-9 h-9 rounded-lg flex items-center justify-center transition-colors
                          ${active
                            ? 'bg-primary-100 dark:bg-primary-900/50 text-primary-600 dark:text-primary-400'
                            : 'bg-slate-100 dark:bg-slate-800 text-slate-500 dark:text-slate-400 group-hover:bg-slate-200 dark:group-hover:bg-slate-700 group-hover:text-slate-700 dark:group-hover:text-white'
                          }`}
                        >
                          <item.icon className="w-5 h-5" />
                        </div>
                        <div className="flex-1 min-w-0">
                          <span className={`text-sm block ${active ? 'font-semibold' : 'font-medium'}`}>
                            {item.label}
                          </span>
                          {item.description && (
                            <span className="text-xs text-slate-400 dark:text-slate-500 truncate block">
                              {item.description}
                            </span>
                          )}
                        </div>
                        {active && <ChevronRight className="w-4 h-4 text-primary-400" />}
                      </Link>
                    );
                  })}
                </div>
              </div>
            ))}
          </div>
        </nav>

        {/* 底部信息 */}
        <div className="p-4 border-t border-slate-100 dark:border-slate-700">
          {currentUser && (
            <div className="px-3 py-3 mb-3 rounded-xl bg-slate-50 dark:bg-slate-800">
              <p className="text-sm font-semibold text-slate-700 dark:text-slate-200 truncate">
                {currentUser.username}
              </p>
              <p className="text-xs text-slate-400 dark:text-slate-500 truncate">
                {currentUser.roles.join(', ') || 'USER'}
              </p>
              <button
                onClick={handleLogout}
                className="mt-3 w-full flex items-center justify-center gap-2 px-3 py-2 rounded-lg text-sm text-slate-600 dark:text-slate-300 bg-white dark:bg-slate-900 hover:text-red-600 dark:hover:text-red-300 transition-colors"
              >
                <LogOut className="w-4 h-4" />
                退出登录
              </button>
            </div>
          )}
          <div className="px-3 py-2 bg-gradient-to-r from-primary-50 to-indigo-50 dark:from-primary-900/30 dark:to-slate-800 rounded-xl">
            <p className="text-xs text-primary-600 dark:text-primary-400 font-medium">AI 面试助手 v1.0</p>
            <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5">Powered by AI</p>
          </div>
        </div>
      </aside>

      {/* 主内容区 */}
      <main className={`flex-1 min-h-screen overflow-y-auto ${
        isFullscreenInterview ? 'ml-0 p-6' : 'ml-64 p-10'
      }`}>
        <motion.div
          key={currentPath}
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: -20 }}
          transition={{ duration: 0.3 }}
        >
          <Suspense fallback={(
            <div className="min-h-[50vh] flex items-center justify-center">
              <div className="w-9 h-9 border-3 border-slate-200 border-t-primary-500 rounded-full animate-spin" />
            </div>
          )}>
            <Outlet />
          </Suspense>
        </motion.div>
      </main>
    </div>
  );
}
