import request from './request';

export interface CurrentUser {
  id: number;
  username: string;
  nickname?: string;
  avatarUrl?: string;
  email?: string;
  mobile?: string;
  status: 'ACTIVE' | 'DISABLED' | 'LOCKED';
  roles: string[];
  permissions: string[];
}

export interface TokenPair {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  user: CurrentUser;
}

export interface LoginPayload {
  username: string;
  password: string;
}

export interface RegisterPayload extends LoginPayload {
  email?: string;
}

export interface PhoneRegisterPayload {
  username: string;
  phone: string;
  verifyCode: string;
  password: string;
}

export interface SendSmsCodeResponse {
  debugCode?: string;
}

export interface SmsLoginPayload {
  mobile: string;
  code: string;
}

export type OAuthProvider = 'wechat' | 'qq';

export interface OAuthAuthorizeResponse {
  authorizationUrl: string;
}

export const authApi = {
  login: (payload: LoginPayload) => request.post<TokenPair>('/api/auth/login', payload),
  register: (payload: RegisterPayload) => request.post<TokenPair>('/api/auth/register', payload),
  sendSmsCode: (phone: string) =>
    request.post<SendSmsCodeResponse>('/api/auth/sms/send', { mobile: phone }),
  loginBySms: (payload: SmsLoginPayload) =>
    request.post<TokenPair>('/api/auth/sms/login', payload),
  getOAuthAuthorization: (provider: OAuthProvider) =>
    request.get<OAuthAuthorizeResponse>(`/api/auth/oauth2/${provider}/authorize`),
  completeOAuthLogin: (provider: OAuthProvider, code: string, state: string) =>
    request.get<TokenPair>(`/api/auth/oauth2/${provider}/callback`, {
      params: { code, state },
    }),
  registerByPhone: (payload: PhoneRegisterPayload) =>
    request.post<TokenPair>('/api/auth/register/phone', payload),
  refresh: (refreshToken: string) => request.post<TokenPair>('/api/auth/refresh', { refreshToken }),
  logout: (refreshToken?: string) => request.post<void>('/api/auth/logout', { refreshToken }),
  me: () => request.get<CurrentUser>('/api/auth/me'),
};
