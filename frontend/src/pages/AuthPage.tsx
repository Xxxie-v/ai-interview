import { type FormEvent, useEffect, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { Loader2, LockKeyhole, MessageSquareText, Sparkles, UserPlus } from 'lucide-react';
import { authApi, type OAuthProvider } from '../api/auth';
import { getErrorMessage } from '../api/request';
import { saveAuth } from '../utils/authStorage';

interface AuthPageProps {
  mode: 'login' | 'register';
}

interface LocationState {
  from?: { pathname?: string };
}

type LoginMethod = 'password' | 'sms';

export default function AuthPage({ mode }: AuthPageProps) {
  const navigate = useNavigate();
  const location = useLocation();
  const state = location.state as LocationState | null;
  const isRegister = mode === 'register';
  const [loginMethod, setLoginMethod] = useState<LoginMethod>('password');
  const [username, setUsername] = useState('');
  const [mobile, setMobile] = useState('');
  const [code, setCode] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [sendingCode, setSendingCode] = useState(false);
  const [oauthLoading, setOauthLoading] = useState<OAuthProvider | null>(null);
  const [smsCooldown, setSmsCooldown] = useState(0);
  const [error, setError] = useState('');
  const [smsMessage, setSmsMessage] = useState('');

  useEffect(() => {
    if (smsCooldown <= 0) return;
    const timer = window.setTimeout(
      () => setSmsCooldown(value => Math.max(value - 1, 0)),
      1000,
    );
    return () => window.clearTimeout(timer);
  }, [smsCooldown]);

  const enterSystem = (tokens: Awaited<ReturnType<typeof authApi.login>>) => {
    saveAuth(tokens);
    const home = tokens.user.roles.includes('ADMIN') ? '/admin/users' : '/history';
    navigate(state?.from?.pathname || home, { replace: true });
  };

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setError('');
    if (isRegister && password !== confirmPassword) {
      setError('两次输入的密码不一致');
      return;
    }
    setLoading(true);
    try {
      if (isRegister) {
        enterSystem(await authApi.registerByPhone({
          username,
          phone: mobile,
          verifyCode: code,
          password,
        }));
      } else if (loginMethod === 'sms') {
        enterSystem(await authApi.loginBySms({ mobile, code }));
      } else {
        enterSystem(await authApi.login({ username, password }));
      }
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  };

  const handleSendCode = async () => {
    setSendingCode(true);
    setError('');
    setSmsMessage('');
    try {
      const result = await authApi.sendSmsCode(mobile);
      setSmsCooldown(60);
      setSmsMessage(result?.debugCode
        ? `本地开发验证码：${result.debugCode}`
        : '验证码已发送，请注意查收');
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setSendingCode(false);
    }
  };

  const handleOAuth = async (provider: OAuthProvider) => {
    setOauthLoading(provider);
    setError('');
    try {
      const result = await authApi.getOAuthAuthorization(provider);
      window.location.assign(result.authorizationUrl);
    } catch (err) {
      setError(getErrorMessage(err));
      setOauthLoading(null);
    }
  };

  const needsSms = isRegister || loginMethod === 'sms';
  const sendCodeDisabled = sendingCode || smsCooldown > 0 || !/^1\d{10}$/.test(mobile);

  return (
    <div className="min-h-screen bg-slate-950 flex items-center justify-center px-6 py-10">
      <div className="w-full max-w-md">
        <div className="flex items-center gap-3 mb-8">
          <div className="w-11 h-11 bg-primary-500 rounded-xl flex items-center justify-center text-white">
            <Sparkles className="w-6 h-6" />
          </div>
          <div>
            <h1 className="text-xl font-bold text-white">企业 AI 视频面试</h1>
            <p className="text-sm text-slate-400">统一身份认证与面试工作台</p>
          </div>
        </div>

        <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-2xl p-8 shadow-2xl">
          <h2 className="text-2xl font-bold text-slate-900 dark:text-white">
            {isRegister ? '创建面试者账号' : '登录系统'}
          </h2>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-1 mb-6">
            {isRegister ? '注册账号只能获得面试者权限' : '请选择一种安全的登录方式'}
          </p>

          {!isRegister && (
            <div className="grid grid-cols-2 gap-2 p-1 mb-6 rounded-xl bg-slate-100 dark:bg-slate-800">
              <button
                type="button"
                onClick={() => setLoginMethod('password')}
                className={`flex items-center justify-center gap-2 py-2 rounded-lg text-sm font-medium ${
                  loginMethod === 'password'
                    ? 'bg-white dark:bg-slate-700 text-primary-600 shadow-sm'
                    : 'text-slate-500'
                }`}
              >
                <LockKeyhole className="w-4 h-4" />账号密码
              </button>
              <button
                type="button"
                onClick={() => setLoginMethod('sms')}
                className={`flex items-center justify-center gap-2 py-2 rounded-lg text-sm font-medium ${
                  loginMethod === 'sms'
                    ? 'bg-white dark:bg-slate-700 text-primary-600 shadow-sm'
                    : 'text-slate-500'
                }`}
              >
                <MessageSquareText className="w-4 h-4" />短信验证码
              </button>
            </div>
          )}

          <form onSubmit={handleSubmit} className="space-y-4">
            {(isRegister || loginMethod === 'password') && (
              <label className="block text-sm font-medium text-slate-700 dark:text-slate-300">
                {isRegister ? '用户名' : '用户名或手机号'}
                <input
                  value={username}
                  onChange={event => setUsername(event.target.value)}
                  required
                  minLength={3}
                  placeholder={isRegister ? '请输入用户名' : '请输入用户名、手机号或 admin'}
                  className="mt-1 w-full px-4 py-2.5 rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-950 text-slate-900 dark:text-white outline-none focus:border-primary-500"
                />
              </label>
            )}

            {needsSms && (
              <>
                <label className="block text-sm font-medium text-slate-700 dark:text-slate-300">
                  手机号
                  <input
                    value={mobile}
                    onChange={event => setMobile(event.target.value)}
                    required
                    pattern="^1\d{10}$"
                    inputMode="tel"
                    placeholder="请输入 11 位手机号"
                    className="mt-1 w-full px-4 py-2.5 rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-950 text-slate-900 dark:text-white outline-none focus:border-primary-500"
                  />
                </label>
                <div>
                  <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1">
                    验证码
                  </label>
                  <div className="flex gap-2">
                    <input
                      value={code}
                      onChange={event => setCode(event.target.value)}
                      required
                      inputMode="numeric"
                      placeholder="6 位验证码"
                      className="flex-1 min-w-0 px-4 py-2.5 rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-950 text-slate-900 dark:text-white outline-none focus:border-primary-500"
                    />
                    <button
                      type="button"
                      onClick={handleSendCode}
                      disabled={sendCodeDisabled}
                      className="w-32 shrink-0 rounded-lg border border-primary-200 text-primary-600 text-sm font-medium hover:bg-primary-50 disabled:opacity-50"
                    >
                      {sendingCode ? '发送中…' : smsCooldown > 0 ? `${smsCooldown} 秒` : '发送验证码'}
                    </button>
                  </div>
                  {smsMessage && <p className="mt-2 text-sm text-emerald-600">{smsMessage}</p>}
                </div>
              </>
            )}

            {(isRegister || loginMethod === 'password') && (
              <label className="block text-sm font-medium text-slate-700 dark:text-slate-300">
                密码
                <input
                  value={password}
                  onChange={event => setPassword(event.target.value)}
                  required
                  minLength={8}
                  type="password"
                  placeholder="至少 8 位"
                  className="mt-1 w-full px-4 py-2.5 rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-950 text-slate-900 dark:text-white outline-none focus:border-primary-500"
                />
              </label>
            )}

            {isRegister && (
              <label className="block text-sm font-medium text-slate-700 dark:text-slate-300">
                确认密码
                <input
                  value={confirmPassword}
                  onChange={event => setConfirmPassword(event.target.value)}
                  required
                  minLength={8}
                  type="password"
                  placeholder="请再次输入密码"
                  className="mt-1 w-full px-4 py-2.5 rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-950 text-slate-900 dark:text-white outline-none focus:border-primary-500"
                />
              </label>
            )}

            {error && (
              <div className="px-4 py-3 rounded-lg bg-red-50 text-red-600 text-sm">{error}</div>
            )}

            <button
              disabled={loading}
              className="w-full h-11 inline-flex items-center justify-center gap-2 rounded-lg bg-primary-500 text-white font-semibold hover:bg-primary-600 disabled:opacity-60"
            >
              {loading ? <Loader2 className="w-4 h-4 animate-spin" /> : <UserPlus className="w-4 h-4" />}
              {isRegister ? '注册并进入系统' : loginMethod === 'sms' ? '验证码登录' : '登录'}
            </button>
          </form>

          {!isRegister && (
            <>
              <div className="flex items-center gap-3 my-6 text-xs text-slate-400">
                <span className="h-px flex-1 bg-slate-200" />第三方账号登录<span className="h-px flex-1 bg-slate-200" />
              </div>
              <div className="grid grid-cols-2 gap-3">
                <button
                  type="button"
                  onClick={() => handleOAuth('wechat')}
                  disabled={oauthLoading !== null}
                  className="h-10 rounded-lg border border-emerald-200 text-emerald-600 font-medium hover:bg-emerald-50 disabled:opacity-50"
                >
                  {oauthLoading === 'wechat' ? '跳转中…' : '微信登录'}
                </button>
                <button
                  type="button"
                  onClick={() => handleOAuth('qq')}
                  disabled={oauthLoading !== null}
                  className="h-10 rounded-lg border border-sky-200 text-sky-600 font-medium hover:bg-sky-50 disabled:opacity-50"
                >
                  {oauthLoading === 'qq' ? '跳转中…' : 'QQ 登录'}
                </button>
              </div>
            </>
          )}

          <div className="mt-6 text-sm text-slate-500">
            {isRegister ? '已有账号？' : '还没有账号？'}
            <Link className="ml-1 text-primary-600 font-medium" to={isRegister ? '/login' : '/register'}>
              {isRegister ? '去登录' : '去注册'}
            </Link>
          </div>
        </div>
      </div>
    </div>
  );
}
