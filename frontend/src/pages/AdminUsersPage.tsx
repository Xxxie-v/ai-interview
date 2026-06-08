import { useCallback, useEffect, useState } from 'react';
import { Loader2, RefreshCw, ShieldCheck, UserCog } from 'lucide-react';
import {
  adminUsersApi,
  type AdminUser,
  type AdminUserPage,
  type UserStatus,
} from '../api/adminUsers';
import { getErrorMessage } from '../api/request';
import { getStoredUser } from '../utils/authStorage';
import {loadPageData, readPageData, writePageData} from '../utils/pageDataCache';

const ADMIN_USERS_CACHE_KEY = 'admin:users';

export function preloadAdminUsersPage(): Promise<AdminUserPage> {
  return loadPageData(ADMIN_USERS_CACHE_KEY, () => adminUsersApi.list());
}

const statusLabels: Record<UserStatus, string> = {
  ACTIVE: '正常',
  DISABLED: '已禁用',
  LOCKED: '已锁定',
};

const statusStyles: Record<UserStatus, string> = {
  ACTIVE: 'bg-emerald-50 text-emerald-700',
  DISABLED: 'bg-slate-100 text-slate-600',
  LOCKED: 'bg-amber-50 text-amber-700',
};

export default function AdminUsersPage() {
  const currentUser = getStoredUser();
  const [data, setData] = useState<AdminUserPage | null>(() =>
    readPageData<AdminUserPage>(ADMIN_USERS_CACHE_KEY));
  const [loading, setLoading] = useState(() =>
    readPageData<AdminUserPage>(ADMIN_USERS_CACHE_KEY) == null);
  const [changingId, setChangingId] = useState<number | null>(null);
  const [error, setError] = useState('');

  const loadUsers = useCallback(async (force = false) => {
    setLoading(readPageData<AdminUserPage>(ADMIN_USERS_CACHE_KEY) == null);
    setError('');
    try {
      setData(await loadPageData(
        ADMIN_USERS_CACHE_KEY,
        () => adminUsersApi.list(),
        {force},
      ));
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadUsers();
  }, [loadUsers]);

  const changeStatus = async (user: AdminUser, status: UserStatus) => {
    setChangingId(user.id);
    setError('');
    try {
      const updated = await adminUsersApi.updateStatus(user.id, status);
      setData(previous => {
        if (!previous) return previous;
        return writePageData(ADMIN_USERS_CACHE_KEY, {
          ...previous,
          items: previous.items.map(item => item.id === updated.id ? updated : item),
        });
      });
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setChangingId(null);
    }
  };

  const changeUnlimitedInterviews = async (user: AdminUser, enabled: boolean) => {
    setChangingId(user.id);
    setError('');
    try {
      const updated = await adminUsersApi.updateUnlimitedInterviews(user.id, enabled);
      setData(previous => {
        if (!previous) return previous;
        return writePageData(ADMIN_USERS_CACHE_KEY, {
          ...previous,
          items: previous.items.map(item => item.id === updated.id ? updated : item),
        });
      });
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setChangingId(null);
    }
  };

  return (
    <div className="max-w-6xl mx-auto">
      <div className="flex items-start justify-between gap-4 mb-8">
        <div>
          <div className="flex items-center gap-3">
            <div className="w-11 h-11 rounded-xl bg-primary-100 text-primary-600 flex items-center justify-center">
              <UserCog className="w-6 h-6" />
            </div>
            <div>
              <h1 className="text-2xl font-bold text-slate-900 dark:text-white">用户与权限</h1>
              <p className="text-sm text-slate-500 mt-1">查看面试者并管理账号状态</p>
            </div>
          </div>
        </div>
        <button
          onClick={() => void loadUsers(true)}
          className="inline-flex items-center gap-2 px-4 py-2 rounded-lg border border-slate-200 bg-white text-slate-600 hover:bg-slate-50"
        >
          <RefreshCw className="w-4 h-4" />刷新
        </button>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 mb-6">
        <Summary label="全部用户" value={data?.totalElements ?? 0} />
        <Summary label="正常账号" value={data?.items.filter(user => user.status === 'ACTIVE').length ?? 0} />
        <Summary label="受限账号" value={data?.items.filter(user => user.status !== 'ACTIVE').length ?? 0} />
      </div>

      {error && <div className="mb-5 px-4 py-3 rounded-lg bg-red-50 text-red-600">{error}</div>}

      <div className="overflow-hidden rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900">
        {loading ? (
          <div className="h-56 flex items-center justify-center">
            <Loader2 className="w-7 h-7 text-primary-500 animate-spin" />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-slate-50 dark:bg-slate-800 text-slate-500">
                <tr>
                  <th className="text-left px-5 py-3 font-medium">用户</th>
                  <th className="text-left px-5 py-3 font-medium">联系方式</th>
                  <th className="text-left px-5 py-3 font-medium">角色</th>
                  <th className="text-left px-5 py-3 font-medium">状态</th>
                  <th className="text-left px-5 py-3 font-medium">测试账号</th>
                  <th className="text-right px-5 py-3 font-medium">操作</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
                {data?.items.map(user => (
                  <tr key={user.id} className="text-slate-700 dark:text-slate-200">
                    <td className="px-5 py-4">
                      <p className="font-semibold">{user.nickname || user.username}</p>
                      <p className="text-xs text-slate-400 mt-1">@{user.username} · ID {user.id}</p>
                    </td>
                    <td className="px-5 py-4 text-slate-500">
                      <p>{user.mobile || '未绑定手机'}</p>
                      {user.email && <p className="text-xs mt-1">{user.email}</p>}
                    </td>
                    <td className="px-5 py-4">
                      <span className="inline-flex items-center gap-1.5">
                        <ShieldCheck className="w-4 h-4 text-primary-500" />
                        {user.roles.join(', ')}
                      </span>
                    </td>
                    <td className="px-5 py-4">
                      <span className={`inline-flex px-2.5 py-1 rounded-full text-xs font-medium ${statusStyles[user.status]}`}>
                        {statusLabels[user.status]}
                      </span>
                    </td>
                    <td className="px-5 py-4">
                      {user.roles.includes('INTERVIEWEE') ? (
                        <label className="inline-flex items-center gap-2 cursor-pointer">
                          <input
                            type="checkbox"
                            checked={user.unlimitedInterviews}
                            disabled={changingId === user.id}
                            onChange={event => void changeUnlimitedInterviews(
                              user,
                              event.target.checked,
                            )}
                            className="h-4 w-4 rounded border-slate-300 text-primary-500"
                          />
                          <span className="text-xs text-slate-500">无限面试</span>
                        </label>
                      ) : <span className="text-xs text-slate-400">不适用</span>}
                    </td>
                    <td className="px-5 py-4 text-right">
                      <select
                        value={user.status}
                        disabled={changingId === user.id || currentUser?.id === user.id}
                        onChange={event => void changeStatus(user, event.target.value as UserStatus)}
                        className="px-3 py-2 rounded-lg border border-slate-200 bg-white dark:bg-slate-950 disabled:opacity-50"
                        aria-label={`修改 ${user.username} 的状态`}
                      >
                        <option value="ACTIVE">恢复正常</option>
                        <option value="DISABLED">禁用账号</option>
                        <option value="LOCKED">锁定账号</option>
                      </select>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {data?.items.length === 0 && (
              <div className="py-16 text-center text-slate-400">暂无用户</div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

function Summary({ label, value }: { label: string; value: number }) {
  return (
    <div className="rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 p-5">
      <p className="text-sm text-slate-500">{label}</p>
      <p className="mt-2 text-2xl font-bold text-slate-900 dark:text-white">{value}</p>
    </div>
  );
}
