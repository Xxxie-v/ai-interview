import axios, { type AxiosInstance, type AxiosRequestConfig } from 'axios';
import { clearAuth, getAccessToken, getRefreshToken, saveAuth } from '../utils/authStorage';
import type { TokenPair } from './auth';

/**
 * 后端统一响应结构
 */
interface Result<T = unknown> {
  code: number;
  message: string;
  data: T;
}

// 默认使用同源地址：开发环境交给 Vite 代理，生产环境交给 Nginx 代理。
// 如需直连独立 API，可通过 VITE_API_BASE_URL 显式覆盖。
const baseURL = (import.meta.env.VITE_API_BASE_URL as string | undefined)
  ?.replace(/\/$/, '') || '';

const instance: AxiosInstance = axios.create({
  baseURL,
  timeout: 60000,
});

interface RetriableRequestConfig extends AxiosRequestConfig {
  _retry?: boolean;
}

export class ApiError extends Error {
  constructor(
    public readonly code: number,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

let refreshingPromise: Promise<string | null> | null = null;

async function refreshAccessToken(): Promise<string | null> {
  const refreshToken = getRefreshToken();
  if (!refreshToken) return null;
  if (!refreshingPromise) {
    refreshingPromise = axios.post<Result<TokenPair>>(
      `${baseURL}/api/auth/refresh`, { refreshToken })
      .then(response => {
        if (response.data.code !== 200 || !response.data.data) {
          clearAuth();
          return null;
        }
        saveAuth(response.data.data);
        return response.data.data.accessToken;
      })
      .catch(() => {
        clearAuth();
        return null;
      })
      .finally(() => {
        refreshingPromise = null;
      });
  }
  return refreshingPromise;
}

instance.interceptors.request.use((config) => {
  const token = getAccessToken();
  if (token) {
    config.headers = config.headers || {};
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

/**
 * 响应拦截器
 * 
 * 后端约定：所有响应都是 HTTP 200 + Result
 * - code === 200 → 成功，返回 data
 * - code !== 200 → 失败，直接显示 message
 */
instance.interceptors.response.use(
  async (response) => {
    const result = response.data as Result;
    
    // 检查是否是 Result 格式
    if (result && typeof result === 'object' && 'code' in result) {
      if (result.code === 200) {
        // 成功：返回 data
        response.data = result.data;
        return response;
      }
      if (result.code === 401) {
        const originalRequest = response.config as RetriableRequestConfig;
        const isAuthEndpoint = originalRequest.url?.includes('/api/auth/');
        if (!originalRequest._retry && !isAuthEndpoint) {
          originalRequest._retry = true;
          const token = await refreshAccessToken();
          if (token) {
            originalRequest.headers = originalRequest.headers || {};
            originalRequest.headers.Authorization = `Bearer ${token}`;
            return instance(originalRequest);
          }
        }
        clearAuth();
        if (!window.location.pathname.startsWith('/login')
            && !window.location.pathname.startsWith('/register')) {
          window.location.href = '/login';
        }
      }
      // 失败：直接抛出 message
      return Promise.reject(new ApiError(result.code, result.message || '请求失败'));
    }
    
    // 非 Result 格式，直接返回
    return response;
  },
  (error) => {
    // 有响应的情况：后端返回了结果（即使是错误）
    if (error.response) {
      const { data } = error.response;
      // 尝试解析 Result 格式
      if (data && typeof data === 'object' && 'code' in data && 'message' in data) {
        const result = data as Result;
        return Promise.reject(new ApiError(result.code, result.message || '请求失败'));
      }
      // 响应格式不对
      return Promise.reject(new Error('请求失败，请重试'));
    }

    // 没有响应的情况：真正的网络错误或连接被重置
    // 对于文件上传，可能是网络超时或连接中断，但不一定是文件大小问题
    // 让后端返回真实的错误信息，而不是在这里假设
    const config = error.config;
    const isUpload = config && (
      config.url?.includes('/upload') ||
      config.headers?.['Content-Type']?.toString().includes('multipart')
    );

    if (isUpload) {
      // 文件上传失败且没有响应，可能是网络超时或连接中断
      // 不直接假设是文件大小问题，返回更通用的错误信息
      return Promise.reject(new Error('上传失败，可能是网络超时或连接中断，请重试'));
    }

    // 其他网络错误
    return Promise.reject(new Error('网络连接失败，请检查网络'));
  }
);

export const request = {
  get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return instance.get(url, config).then(res => res.data);
  },

  post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return instance.post(url, data, config).then(res => res.data);
  },

  put<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return instance.put(url, data, config).then(res => res.data);
  },

  patch<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return instance.patch(url, data, config).then(res => res.data);
  },

  delete<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return instance.delete(url, config).then(res => res.data);
  },

  /**
   * 文件上传
   */
  upload<T>(url: string, formData: FormData, config?: AxiosRequestConfig): Promise<T> {
    return instance.post(url, formData, {
      timeout: 300000, // 5分钟，与Nginx proxy_read_timeout对齐
      headers: { 'Content-Type': 'multipart/form-data' },
      ...config,
    }).then(res => res.data);
  },

  /**
   * 获取原始实例（用于特殊场景如下载 Blob）
   */
  getInstance(): AxiosInstance {
    return instance;
  },
};

/**
 * 获取错误信息
 */
export function getErrorMessage(error: unknown): string {
  if (error instanceof Error) {
    return error.message;
  }
  return '未知错误';
}

export default request;
