import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { Loader2 } from 'lucide-react';
import { authApi, type OAuthProvider } from '../api/auth';
import { getErrorMessage } from '../api/request';
import { saveAuth } from '../utils/authStorage';

export default function OAuthCallbackPage() {
  const navigate = useNavigate();
  const { provider } = useParams<{ provider: string }>();
  const [searchParams] = useSearchParams();
  const [error, setError] = useState('');

  useEffect(() => {
    const code = searchParams.get('code');
    const state = searchParams.get('state');
    if ((provider !== 'wechat' && provider !== 'qq') || !code || !state) {
      setError('第三方登录回调参数不完整');
      return;
    }
    authApi.completeOAuthLogin(provider as OAuthProvider, code, state)
      .then(tokens => {
        saveAuth(tokens);
        navigate(tokens.user.roles.includes('ADMIN') ? '/admin/users' : '/history', {
          replace: true,
        });
      })
      .catch(err => setError(getErrorMessage(err)));
  }, [navigate, provider, searchParams]);

  return (
    <div className="min-h-screen bg-slate-950 flex items-center justify-center px-6">
      <div className="w-full max-w-md rounded-2xl bg-white p-8 text-center shadow-2xl">
        {error ? (
          <>
            <h1 className="text-xl font-bold text-red-600">登录失败</h1>
            <p className="mt-3 text-sm text-slate-500">{error}</p>
            <Link to="/login" className="inline-block mt-6 text-primary-600 font-medium">
              返回登录页
            </Link>
          </>
        ) : (
          <>
            <Loader2 className="w-9 h-9 mx-auto text-primary-500 animate-spin" />
            <h1 className="mt-4 text-xl font-bold text-slate-900">正在完成安全登录</h1>
            <p className="mt-2 text-sm text-slate-500">正在验证第三方授权信息，请稍候……</p>
          </>
        )}
      </div>
    </div>
  );
}
