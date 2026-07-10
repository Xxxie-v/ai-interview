import {useEffect, useRef, useState} from 'react';
import {
  Camera,
  CheckCircle2,
  Loader2,
  Mic,
  MonitorUp,
  ShieldCheck,
  TriangleAlert,
} from 'lucide-react';

type DeviceState = 'IDLE' | 'REQUESTING_PERMISSION' | 'READY' | 'ERROR';

interface InterviewDeviceCheckProps {
  onStartRecording: (stream: MediaStream, screenStream: MediaStream) => void;
  onReady: (stream: MediaStream, screenStream: MediaStream) => Promise<void>;
  onStartFailed: () => Promise<void>;
  onPrepareInterview: () => Promise<void>;
  onPrepareMedia?: (stream: MediaStream) => Promise<void>;
  preparingInterview?: boolean;
}

type EntryState = 'IDLE' | 'PREPARING' | 'READY' | 'ERROR';

function deviceErrorMessage(error: unknown): string {
  if (error instanceof DOMException) {
    if (error.name === 'NotAllowedError') return '摄像头或麦克风权限被拒绝，请在浏览器地址栏中允许后重试。';
    if (error.name === 'NotFoundError') return '没有检测到可用的摄像头或麦克风。';
    if (error.name === 'NotReadableError') return '设备可能正被其他软件占用，请关闭占用设备的软件后重试。';
    if (error.name === 'InvalidStateError' || error.name === 'AbortError') {
      return '屏幕共享权限请求未完成，请保持页面在前台并重新点击检测。';
    }
  }
  if (error instanceof Error && error.message.includes('Permissions check failed')) {
    return '浏览器未完成屏幕共享权限校验，请保持页面在前台后重试。';
  }
  return '设备检查失败，请确认浏览器支持摄像头和麦克风。';
}

export default function InterviewDeviceCheck({
  onStartRecording,
  onReady,
  onStartFailed,
  onPrepareInterview,
  onPrepareMedia,
  preparingInterview = false,
}: InterviewDeviceCheckProps) {
  const [state, setState] = useState<DeviceState>('IDLE');
  const [error, setError] = useState('');
  const [permissionStage, setPermissionStage] = useState('');
  const [starting, setStarting] = useState(false);
  const [fullscreenReady, setFullscreenReady] = useState(Boolean(document.fullscreenElement));
  const [entryState, setEntryState] = useState<EntryState>('IDLE');
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const screenStreamRef = useRef<MediaStream | null>(null);
  const transferredRef = useRef(false);
  const preparingEntryRef = useRef(false);

  useEffect(() => () => {
    if (!transferredRef.current) {
      streamRef.current?.getTracks().forEach(track => track.stop());
      screenStreamRef.current?.getTracks().forEach(track => track.stop());
    }
  }, []);

  useEffect(() => {
    const handleFullscreenChange = () => {
      const active = Boolean(document.fullscreenElement);
      setFullscreenReady(active);
      if (!active && state === 'READY') {
        setError('已退出全屏，请重新进入全屏后开始面试。');
      }
    };
    document.addEventListener('fullscreenchange', handleFullscreenChange);
    return () => document.removeEventListener('fullscreenchange', handleFullscreenChange);
  }, [state]);

  const prepareInterviewEntry = async () => {
    if (preparingEntryRef.current) return;
    preparingEntryRef.current = true;
    setEntryState('PREPARING');
    setError('');
    try {
      await onPrepareInterview();
      setEntryState('READY');
    } catch (cause) {
      setEntryState('ERROR');
      setError(cause instanceof Error ? cause.message : '第一题准备失败，请重试。');
    } finally {
      preparingEntryRef.current = false;
    }
  };

  useEffect(() => {
    if (state !== 'READY' || preparingInterview || entryState !== 'IDLE') return;
    void prepareInterviewEntry();
  }, [state, preparingInterview, entryState]);

  const requestDevices = async () => {
    setState('REQUESTING_PERMISSION');
    setError('');
    setPermissionStage('正在请求整个屏幕共享');
    streamRef.current?.getTracks().forEach(track => track.stop());
    screenStreamRef.current?.getTracks().forEach(track => track.stop());
    let acquiredStream: MediaStream | null = null;
    let acquiredScreenStream: MediaStream | null = null;
    try {
      if (!navigator.mediaDevices?.getUserMedia || !navigator.mediaDevices.getDisplayMedia) {
        throw new Error('MEDIA_DEVICES_UNSUPPORTED');
      }
      // 按界面承诺的顺序执行：屏幕共享 -> 全屏 -> 摄像头和麦克风。
      const screenStream = await navigator.mediaDevices.getDisplayMedia({
        video: {displaySurface: 'monitor'},
        audio: false,
      });
      acquiredScreenStream = screenStream;
      const screenTrack = screenStream.getVideoTracks()[0];
      if (!screenTrack) {
        screenStream.getTracks().forEach(track => track.stop());
        throw new Error('未获取到屏幕共享画面');
      }
      const displaySurface = screenTrack.getSettings().displaySurface;
      if (displaySurface && displaySurface !== 'monitor') {
        screenStream.getTracks().forEach(track => track.stop());
        throw new Error('正式面试必须共享整个屏幕，不能只共享窗口或标签页');
      }

      setPermissionStage('正在进入全屏并启动摄像头、麦克风');
      const fullscreenPromise = document.fullscreenElement
        ? Promise.resolve()
        : document.documentElement.requestFullscreen();
      // 全屏请求已经先发起；摄像头硬件启动与全屏动画并行，
      // 避免两段耗时串行累加。
      const devicePromise = navigator.mediaDevices.getUserMedia({
        video: {width: {ideal: 1280}, height: {ideal: 720}, facingMode: 'user'},
        audio: {echoCancellation: true, noiseSuppression: true, autoGainControl: true},
      });
      const [fullscreenResult, deviceResult] = await Promise.allSettled([
        fullscreenPromise,
        devicePromise,
      ]);
      if (fullscreenResult.status === 'rejected' || deviceResult.status === 'rejected') {
        if (deviceResult.status === 'fulfilled') {
          deviceResult.value.getTracks().forEach(track => track.stop());
        }
        if (fullscreenResult.status === 'rejected') {
          throw new Error('屏幕共享已授权，但浏览器拒绝进入全屏，请允许全屏权限后重试');
        }
        throw deviceResult.status === 'rejected'
          ? deviceResult.reason
          : new Error('摄像头或麦克风启动失败');
      }
      if (!document.fullscreenElement) throw new Error('未能进入全屏模式');
      setFullscreenReady(true);
      const stream = deviceResult.value;
      acquiredStream = stream;

      if (!stream.getVideoTracks().length || !stream.getAudioTracks().length) {
        stream.getTracks().forEach(track => track.stop());
        screenStream.getTracks().forEach(track => track.stop());
        throw new DOMException('Required device missing', 'NotFoundError');
      }
      streamRef.current = stream;
      screenStreamRef.current = screenStream;
      setEntryState('IDLE');
      if (videoRef.current) videoRef.current.srcObject = stream;
      setPermissionStage('');
      setState('READY');
    } catch (cause) {
      acquiredStream?.getTracks().forEach(track => track.stop());
      acquiredScreenStream?.getTracks().forEach(track => track.stop());
      setPermissionStage('');
      setState('ERROR');
      setError(deviceErrorMessage(cause));
    }
  };

  const beginInterview = async () => {
    if (!streamRef.current || !screenStreamRef.current || entryState !== 'READY') return;
    setStarting(true);
    setError('');
    try {
      // 权限、第一题和 WebSocket 均已在按钮启用前确认。
      // 点击后先开始本地录制，再在同一用户激活中发起全屏请求。
      onStartRecording(streamRef.current, screenStreamRef.current);
      const fullscreenRequest = document.fullscreenElement
        ? Promise.resolve()
        : document.documentElement.requestFullscreen();
      await fullscreenRequest;
      if (!document.fullscreenElement) {
        throw new Error('正式面试必须进入全屏模式');
      }
      transferredRef.current = true;
      // 先切换到正式题目页，再在后台启动语音采集。
      // AudioContext 初始化不应该让开始按钮持续转圈。
      await onReady(streamRef.current, screenStreamRef.current);
      void onPrepareMedia?.(streamRef.current);
    } catch (cause) {
      transferredRef.current = false;
      await onStartFailed();
      if (document.fullscreenElement) {
        await document.exitFullscreen().catch(() => undefined);
      }
      setError(cause instanceof Error ? cause.message : '面试启动失败，请重试。');
    } finally {
      setStarting(false);
    }
  };

  return (
    <div className="mx-auto max-w-5xl" data-testid="interview-device-check">
      <div className="mb-6 rounded-2xl border border-primary-200 bg-primary-50 p-5 dark:border-primary-800 dark:bg-primary-950/30">
        <div className="flex gap-3">
          <ShieldCheck className="mt-0.5 h-6 w-6 shrink-0 text-primary-600" />
          <div>
            <h2 className="font-semibold text-slate-900 dark:text-white">面试设备与录制授权</h2>
            <p className="mt-1 text-sm leading-6 text-slate-600 dark:text-slate-300">
              正式面试会采集摄像头和麦克风，并每隔约 25 秒加密上传一个视频分片。
              面试中会定期抽取低清画面检查低光、摄像头中断等客观状态，抽帧默认不保存。
              视频仅供本次招聘审核使用。点击检测后，系统会依次请求屏幕共享、进入全屏，然后检测摄像头和麦克风；点击开始面试后才会录制。
            </p>
          </div>
        </div>
      </div>

      <div className="grid gap-6 lg:grid-cols-[1.4fr_1fr]">
        <div className="relative aspect-video overflow-hidden rounded-2xl bg-slate-950 shadow-xl">
          <video ref={videoRef} autoPlay muted playsInline className="h-full w-full object-cover" />
          {state !== 'READY' && (
            <div className="absolute inset-0 flex items-center justify-center text-slate-400">
              <Camera className="h-14 w-14" />
            </div>
          )}
        </div>

        <div className="rounded-2xl border border-slate-200 bg-white p-6 dark:border-slate-700 dark:bg-slate-800">
          <h3 className="font-semibold text-slate-900 dark:text-white">检查项目</h3>
          <div className="mt-5 space-y-4">
            {[
              {label: '摄像头画面', icon: Camera},
              {label: '麦克风输入', icon: Mic},
              {label: '整个屏幕共享', icon: MonitorUp},
            ].map(item => (
              <div key={item.label} className="flex items-center justify-between rounded-xl bg-slate-50 p-4 dark:bg-slate-900/50">
                <span className="flex items-center gap-3 text-sm text-slate-700 dark:text-slate-200">
                  <item.icon className="h-5 w-5" />{item.label}
                </span>
                {state === 'READY'
                  ? <CheckCircle2 className="h-5 w-5 text-emerald-500" />
                  : <span className="text-xs text-slate-400">待检查</span>}
              </div>
            ))}
          </div>

          {error && (
            <div className="mt-4 flex gap-2 rounded-lg bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950/30 dark:text-red-300">
              <TriangleAlert className="h-5 w-5 shrink-0" />{error}
            </div>
          )}

          {preparingInterview && (
            <div className="mt-4 flex items-center gap-2 rounded-lg bg-primary-50 p-3 text-sm text-primary-700 dark:bg-primary-950/30 dark:text-primary-300">
              <Loader2 className="h-4 w-4 animate-spin" />
              后台正在准备本场题目，可继续调试摄像头和麦克风
            </div>
          )}

          {!preparingInterview && entryState === 'PREPARING' && (
            <div className="mt-4 flex items-center gap-2 rounded-lg bg-primary-50 p-3 text-sm text-primary-700 dark:bg-primary-950/30 dark:text-primary-300">
              <Loader2 className="h-4 w-4 animate-spin" />
              权限已确认，正在确认第一题和实时连接
            </div>
          )}

          {state !== 'READY' || !fullscreenReady ? (
            <button
              type="button"
              onClick={requestDevices}
              disabled={state === 'REQUESTING_PERMISSION'}
              className="mt-6 flex h-11 w-full items-center justify-center gap-2 rounded-lg bg-primary-500 font-semibold text-white hover:bg-primary-600 disabled:opacity-60"
            >
              {state === 'REQUESTING_PERMISSION' && <Loader2 className="h-4 w-4 animate-spin" />}
              {state === 'REQUESTING_PERMISSION'
                ? permissionStage || '正在准备面试环境'
                : '检测面试环境'}
            </button>
          ) : (
            entryState === 'ERROR' ? (
              <button
                type="button"
                onClick={() => void prepareInterviewEntry()}
                className="mt-6 flex h-11 w-full items-center justify-center rounded-lg bg-primary-500 font-semibold text-white hover:bg-primary-600"
              >
                重新确认面试题
              </button>
            ) : (
              <button
                type="button"
                onClick={beginInterview}
                disabled={starting || preparingInterview || entryState !== 'READY'}
                className="mt-6 flex h-11 w-full items-center justify-center gap-2 rounded-lg bg-emerald-500 font-semibold text-white hover:bg-emerald-600 disabled:opacity-60"
              >
                {(starting || preparingInterview || entryState === 'PREPARING')
                  && <Loader2 className="h-4 w-4 animate-spin" />}
                {preparingInterview
                  ? '题目准备中，请稍候'
                  : entryState === 'PREPARING'
                    ? '正在确认第一题'
                    : starting
                      ? '正在录制并准备面试…'
                      : '同意录制并开始面试'}
              </button>
            )
          )}
        </div>
      </div>
    </div>
  );
}
