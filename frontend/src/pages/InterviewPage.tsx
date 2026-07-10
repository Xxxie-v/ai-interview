import {useEffect, useRef, useState} from 'react';
import {flushSync} from 'react-dom';
import {motion} from 'framer-motion';
import {interviewApi} from '../api/interview';
import ConfirmDialog from '../components/ConfirmDialog';
import InterviewChatPanel from '../components/InterviewChatPanel';
import InterviewDeviceCheck from '../components/InterviewDeviceCheck';
import type {InterviewQuestion, InterviewSession} from '../types/interview';
import type {Difficulty} from '../components/UnifiedInterviewModal';
import type {CategoryDTO} from '../api/skill';
import { CUSTOM_SKILL_ID } from '../hooks/useInterviewConfig';
import {
  InterviewEventSocket,
  resolveInterviewWebSocketUrl,
  type InterviewSocketEvent,
} from '../api/interviewEvents';
import {interviewVideoApi} from '../api/interviewVideos';
import {interviewVisionApi, type VisionEventType} from '../api/interviewVision';
import {InterviewMediaRecorder} from '../utils/interviewMediaRecorder';
import {InterviewVisionMonitor} from '../utils/interviewVisionMonitor';
import {InterviewProctorMonitor} from '../utils/interviewProctorMonitor';
import {InterviewSpeechCapture} from '../utils/interviewSpeechCapture';
import {
  countPendingInterviewUploads,
  flushPendingInterviewUploads,
  resolveNextVideoChunkIndex,
  uploadProctorEventReliably,
  uploadVideoChunkReliably,
} from '../utils/interviewUploadQueue';

const CREATE_SESSION_TIMEOUT_MS = 100_000;
const QUESTION_AI_WAIT_SECONDS = 90;

interface Message {
  type: 'interviewer' | 'user';
  content: string;
  category?: string;
  questionIndex?: number;
}

interface InterviewProps {
  resumeText: string;
  resumeId?: number;
  sessionIdToResume?: string;
  initialConfig?: {
    questionCount?: number;
    llmProvider?: string;
    skillId?: string;
    difficulty?: Difficulty;
    customCategories?: CategoryDTO[];
    jdText?: string;
    officialInterview?: boolean;
    jobId?: number;
  };
  onBack: () => void;
  onInterviewComplete: () => void;
}

export default function Interview({
  resumeText,
  resumeId,
  sessionIdToResume,
  initialConfig,
  onBack,
  onInterviewComplete,
}: InterviewProps) {
  const [session, setSession] = useState<InterviewSession | null>(null);
  const [currentQuestion, setCurrentQuestion] = useState<InterviewQuestion | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [answer, setAnswer] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [waitingForNextQuestion, setWaitingForNextQuestion] = useState(false);
  const [error, setError] = useState('');
  const [isCreating, setIsCreating] = useState(false);
  const [creatingElapsedSeconds, setCreatingElapsedSeconds] = useState(0);
  const [showCompleteConfirm, setShowCompleteConfirm] = useState(false);
  const [deviceReady, setDeviceReady] = useState(!Boolean(initialConfig?.officialInterview));
  const [recordingWarning, setRecordingWarning] = useState('');
  const [visionWarning, setVisionWarning] = useState('');
  const [proctorWarning, setProctorWarning] = useState('');
  const [proctorBlocked, setProctorBlocked] = useState(false);
  const [speechReady, setSpeechReady] = useState(false);
  const [readingQuestion, setReadingQuestion] = useState(false);
  const [partialTranscript, setPartialTranscript] = useState('');
  const [answerMode, setAnswerMode] = useState<'text' | 'voice'>('text');
  const startedRef = useRef(false);
  const eventSocketRef = useRef<InterviewEventSocket | null>(null);
  const mediaStreamRef = useRef<MediaStream | null>(null);
  const mediaRecorderRef = useRef<InterviewMediaRecorder | null>(null);
  const visionMonitorRef = useRef<InterviewVisionMonitor | null>(null);
  const screenStreamRef = useRef<MediaStream | null>(null);
  const proctorMonitorRef = useRef<InterviewProctorMonitor | null>(null);
  const speechCaptureRef = useRef<InterviewSpeechCapture | null>(null);
  const answerModeRef = useRef<'text' | 'voice'>('text');
  const audioContextRef = useRef<AudioContext | null>(null);
  const questionAudioSourceRef = useRef<AudioBufferSourceNode | null>(null);
  const recordingStartedAtRef = useRef<number | null>(null);
  const nextVideoChunkIndexRef = useRef(0);

  const questionCount = initialConfig?.questionCount ?? 8;
  const llmProvider = initialConfig?.llmProvider ?? '';
  const skillId = initialConfig?.skillId ?? 'java-backend';
  const difficulty = initialConfig?.difficulty ?? 'mid';
  const customCategories = initialConfig?.customCategories;
  const jdText = initialConfig?.jdText;
  const officialInterview = Boolean(initialConfig?.officialInterview);
  const jobId = initialConfig?.jobId;
  const createProgress = isCreating
    ? Math.min(95, Math.max(8, Math.round((creatingElapsedSeconds / QUESTION_AI_WAIT_SECONDS) * 95)))
    : 0;
  const createStage = creatingElapsedSeconds < 8
    ? '正在连接 AI 出题模型'
    : creatingElapsedSeconds < 35
      ? 'AI 正在生成结构化面试题'
      : creatingElapsedSeconds < QUESTION_AI_WAIT_SECONDS
        ? 'AI 响应较慢，仍在优先等待真实题目'
        : '等待时间较长，正在尝试恢复已创建的会话';

  const sleep = (ms: number) => new Promise(resolve => window.setTimeout(resolve, ms));

  const withTimeout = async <T,>(promise: Promise<T>, timeoutMs: number): Promise<T> => {
    let timeoutId: number | undefined;
    const timeout = new Promise<never>((_, reject) => {
      timeoutId = window.setTimeout(() => reject(new Error('CREATE_INTERVIEW_TIMEOUT')), timeoutMs);
    });
    try {
      return await Promise.race([promise, timeout]);
    } finally {
      if (timeoutId !== undefined) {
        window.clearTimeout(timeoutId);
      }
    }
  };

  // 自动开始面试（恢复已有会话 或 创建新会话）
  useEffect(() => {
    if (!startedRef.current) {
      startedRef.current = true;
      if (sessionIdToResume) {
        resumeExistingSession(sessionIdToResume);
      } else {
        startInterview();
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (!isCreating) {
      setCreatingElapsedSeconds(0);
      return;
    }

    setCreatingElapsedSeconds(0);
    const timerId = window.setInterval(() => {
      setCreatingElapsedSeconds(prev => prev + 1);
    }, 1000);

    return () => window.clearInterval(timerId);
  }, [isCreating]);

  const startInterview = async () => {
    setIsCreating(true);
    setError('');

    try {
      const createSessionPromise = interviewApi.createSession({
        resumeText,
        questionCount,
        resumeId,
        forceCreate: !jobId,
        llmProvider,
        skillId,
        difficulty,
        customCategories: skillId === CUSTOM_SKILL_ID ? customCategories : undefined,
        jdText: skillId === CUSTOM_SKILL_ID ? jdText : undefined,
        officialInterview,
        jobId,
      });
      const newSession = await withTimeout(createSessionPromise, CREATE_SESSION_TIMEOUT_MS)
        .catch(async err => {
          if (err instanceof Error && err.message === 'CREATE_INTERVIEW_TIMEOUT') {
            return recoverLatestCreatedSession();
          }
          throw err;
        });

      console.debug('Interview session created:', {
        sessionId: newSession.sessionId,
        questionCount: newSession.questions?.length,
      });
      initSession(newSession);
    } catch (err) {
      setError('创建面试失败，请重试');
      console.error(err);
    } finally {
      setIsCreating(false);
    }
  };

  const resumeExistingSession = async (sessionId: string) => {
    setIsCreating(true);
    setError('');

    try {
      const existingSession = await interviewApi.getSession(sessionId);
      initSession(existingSession);

      // 恢复已填写的答案
      const currentQ = existingSession.questions[existingSession.currentQuestionIndex];
      if (currentQ?.userAnswer) {
        setAnswer(currentQ.userAnswer);
      }
    } catch (err) {
      setError('恢复面试失败，请重试');
      console.error(err);
    } finally {
      setIsCreating(false);
    }
  };

  const initSession = (s: InterviewSession) => {
    setSession(s);

    if (s.questions.length > 0) {
      const idx = Math.min(s.currentQuestionIndex, s.questions.length - 1);
      const currentQ = s.questions[idx];
      setCurrentQuestion(currentQ);

      // 只恢复当前主问题及其追问。进入下一道主问题时切换到新界面，
      // 不把上一组问答继续堆在当前面试界面中。
      const restoredMessages: Message[] = [];
      const groupStart = currentQ.isFollowUp
        ? currentQ.parentQuestionIndex ?? idx
        : idx;
      for (let i = groupStart; i <= idx; i++) {
        const q = s.questions[i];
        restoredMessages.push({
          type: 'interviewer',
          content: q.question,
          category: q.category,
          questionIndex: i
        });
        if (q.userAnswer) {
          restoredMessages.push({
            type: 'user',
            content: q.userAnswer
          });
        }
      }
      setMessages(restoredMessages);
    } else if (s.questionPrepareStatus === 'FAILED') {
      setError(s.questionPrepareError || '面试题目准备失败，请返回后重试');
    }

    if (jobId && s.webSocketPath) {
      eventSocketRef.current?.close();
      const socket = new InterviewEventSocket(
        s.sessionId,
        resolveInterviewWebSocketUrl(s.webSocketPath),
        {
          onOpen: () => undefined,
          onEvent: handleInterviewEvent,
          onError: () => setError('实时面试连接异常，正在尝试恢复'),
        },
      );
      eventSocketRef.current = socket;
      socket.connect();
    }
  };

  const retryQuestionPreparation = async () => {
    if (!session) return;
    setIsCreating(true);
    setError('');
    try {
      const retried = await interviewApi.retryQuestionPreparation(session.sessionId);
      initSession(retried);
    } catch (cause) {
      console.error('Failed to retry question preparation:', cause);
      setError('重新准备面试题目失败，请稍后再试');
    } finally {
      setIsCreating(false);
    }
  };

  const finalizeVideoRecording = async () => {
    const stream = mediaStreamRef.current;
    const screenStream = screenStreamRef.current;
    visionMonitorRef.current?.stop();
    visionMonitorRef.current = null;
    proctorMonitorRef.current?.stop();
    proctorMonitorRef.current = null;
    try {
      questionAudioSourceRef.current?.stop();
    } catch {
      // 音频已经结束。
    }
    try {
      await speechCaptureRef.current?.stop();
      await mediaRecorderRef.current?.stop();
      if (session && mediaRecorderRef.current) {
        await flushPendingInterviewUploads();
        const pendingCount = await countPendingInterviewUploads(session.sessionId);
        if (pendingCount > 0) {
          throw new Error(`仍有 ${pendingCount} 个面试证据等待补传`);
        }
        await interviewVideoApi.complete(session.sessionId);
      }
    } catch (cause) {
      console.error('Interview video finalization failed:', cause);
      setRecordingWarning('面试已经提交，但视频上传尚未完整结束，请联系管理员核查。');
    } finally {
      stream?.getTracks().forEach(track => track.stop());
      screenStream?.getTracks().forEach(track => track.stop());
      mediaStreamRef.current = null;
      screenStreamRef.current = null;
      mediaRecorderRef.current = null;
      speechCaptureRef.current = null;
      recordingStartedAtRef.current = null;
    }
  };

  const handleInterviewEvent = (event: InterviewSocketEvent) => {
    if (event.type === 'QUESTIONS_READY') {
      void interviewApi.getSession(event.sessionId)
        .then(preparedSession => initSession(preparedSession))
        .catch(cause => {
          console.error('Failed to refresh prepared questions:', cause);
          setError('题目已生成，但刷新失败，请重新进入面试');
        });
    } else if (event.type === 'QUESTIONS_FAILED') {
      setError(String(event.payload.message || '面试题目准备失败，请返回后重试'));
    } else if (event.type === 'NEW_QUESTION') {
      setWaitingForNextQuestion(false);
      setIsSubmitting(false);
      const nextQuestion = event.payload.question as InterviewQuestion | undefined;
      if (nextQuestion) {
        setMessages(items => {
          if (!nextQuestion.isFollowUp) {
            return [{
              type: 'interviewer',
              content: nextQuestion.question,
              category: nextQuestion.category,
              questionIndex: nextQuestion.questionIndex,
            }];
          }
          const alreadyDisplayed = items.some(item =>
            item.type === 'interviewer'
            && item.questionIndex === nextQuestion.questionIndex);
          if (alreadyDisplayed) return items;
          return [...items, {
            type: 'interviewer',
            content: nextQuestion.question,
            category: nextQuestion.category,
            questionIndex: nextQuestion.questionIndex,
          }];
        });
        setCurrentQuestion(nextQuestion);
      }
    } else if (event.type === 'NEXT_QUESTION_PENDING') {
      setIsSubmitting(false);
      setWaitingForNextQuestion(true);
    } else if (event.type === 'ANSWER_ACCEPTED') {
      setIsSubmitting(false);
    } else if (event.type === 'SPEECH_RECOGNITION_READY') {
      setSpeechReady(true);
      speechCaptureRef.current?.setPaused(answerModeRef.current !== 'voice');
    } else if (event.type === 'ANSWER_TRANSCRIPT') {
      const text = String(event.payload.text || '').trim();
      if (!text) return;
      if (Boolean(event.payload.final)) {
        setAnswer(previous => `${previous.trim()}${previous.trim() ? '，' : ''}${text}`);
        setPartialTranscript('');
      } else {
        setPartialTranscript(text);
      }
    } else if (event.type === 'QUESTION_AUDIO_READY') {
      const data = String(event.payload.data || '');
      const mimeType = String(event.payload.mimeType || 'audio/wav');
      if (data) void playQuestionAudio(data, mimeType);
    } else if (event.type === 'INTERVIEW_FINISHED') {
      setIsSubmitting(false);
      setWaitingForNextQuestion(false);
      eventSocketRef.current?.close();
      void finalizeVideoRecording().finally(onInterviewComplete);
    } else if (event.type === 'ERROR') {
      setIsSubmitting(false);
      setWaitingForNextQuestion(false);
      setReadingQuestion(false);
      speechCaptureRef.current?.setPaused(answerModeRef.current !== 'voice');
      setError(String(event.payload.message || '实时面试处理失败，请重试'));
    }
  };

  useEffect(() => {
    if (!session
        || session.questionPrepareStatus === 'COMPLETED'
        || session.questionPrepareStatus === 'FAILED') {
      return;
    }
    let stopped = false;
    const refresh = async () => {
      try {
        const latest = await interviewApi.getSession(session.sessionId);
        if (stopped) return;
        if (latest.questionPrepareStatus === 'COMPLETED') {
          initSession(latest);
        } else if (latest.questionPrepareStatus === 'FAILED') {
          setSession(latest);
          setError(latest.questionPrepareError || '面试题目准备失败，请返回后重试');
        } else {
          setSession(latest);
        }
      } catch (cause) {
        console.error('Question preparation status refresh failed:', cause);
      }
    };
    const timerId = window.setInterval(() => void refresh(), 1500);
    return () => {
      stopped = true;
      window.clearInterval(timerId);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [session?.sessionId, session?.questionPrepareStatus]);

  useEffect(() => () => {
    eventSocketRef.current?.close();
    visionMonitorRef.current?.stop();
    proctorMonitorRef.current?.stop();
    try {
      questionAudioSourceRef.current?.stop();
    } catch {
      // 音频已经结束。
    }
    void audioContextRef.current?.close();
    const stream = mediaStreamRef.current;
    const screenStream = screenStreamRef.current;
    void speechCaptureRef.current?.stop();
    void mediaRecorderRef.current?.stop().finally(() => {
      stream?.getTracks().forEach(track => track.stop());
      screenStream?.getTracks().forEach(track => track.stop());
    });
  }, []);

  const handleRecordingStart = (stream: MediaStream, screenStream: MediaStream) => {
    if (!session) throw new Error('面试会话尚未创建完成');
    mediaStreamRef.current = stream;
    screenStreamRef.current = screenStream;
    recordingStartedAtRef.current = Date.now();
    void flushPendingInterviewUploads();
    const recorder = new InterviewMediaRecorder();
    mediaRecorderRef.current = recorder;
    recorder.start(
      stream,
      Number(import.meta.env.VITE_INTERVIEW_VIDEO_CHUNK_MS || 25_000),
      nextVideoChunkIndexRef.current,
      async chunk => {
        nextVideoChunkIndexRef.current = Math.max(
          nextVideoChunkIndexRef.current,
          chunk.index + 1,
        );
        try {
          const uploaded = await uploadVideoChunkReliably(
            session.sessionId,
            chunk.index,
            chunk.durationMs,
            chunk.blob,
          );
          eventSocketRef.current?.send('VIDEO_CHUNK_UPLOADED', {
            chunkIndex: uploaded.chunkIndex,
            checksum: uploaded.checksum,
          });
        } catch (cause) {
          console.error('Interview video chunk upload failed:', cause);
          setRecordingWarning('有视频分片上传失败，系统已重试；结束面试前请保持页面打开。');
        }
      },
    );
  };

  const handleDeviceReady = async (stream: MediaStream, screenStream: MediaStream) => {
    if (!session) throw new Error('面试会话尚未创建完成');
    eventSocketRef.current?.send('DEVICE_READY');
    const started = eventSocketRef.current?.send('START_INTERVIEW') ?? false;
    if (!started) {
      throw new Error('实时面试连接未就绪，请退出全屏后重试');
    }

    // 录制已经开始，全屏成功后再切换到正式题目页。
    flushSync(() => setDeviceReady(true));

    const visionMonitor = new InterviewVisionMonitor();
    visionMonitorRef.current = visionMonitor;
    void visionMonitor.start(
        stream,
        Number(import.meta.env.VITE_INTERVIEW_VISION_FRAME_MS || 5_000),
        async sample => {
          try {
            const result = await interviewVisionApi.analyze(
              session.sessionId,
              sample.frame,
              sample.brightness,
              sample.cameraActive,
              recordingStartedAtRef.current == null
                ? undefined
                : Math.max(0, Date.now() - recordingStartedAtRef.current),
            );
            setVisionWarning(visionWarningMessage(result.events));
            return result.recommendedIntervalMs;
          } catch (cause) {
            console.error('Interview vision analysis failed:', cause);
            return undefined;
          }
        },
      ).catch(cause => {
      console.error('Interview vision monitor failed:', cause);
      setVisionWarning('画面状态检测暂时不可用，但视频录制仍在继续。');
      });

    const proctorMonitor = new InterviewProctorMonitor();
    proctorMonitorRef.current = proctorMonitor;
    void proctorMonitor.start(
      screenStream,
      Number(import.meta.env.VITE_INTERVIEW_SCREEN_CAPTURE_MS || 30_000),
      async proctorEvent => {
        if (proctorEvent.eventType === 'FULLSCREEN_EXIT') {
          setProctorBlocked(true);
          setProctorWarning('检测到退出全屏，请立即重新进入全屏后继续作答。');
        } else if (proctorEvent.eventType === 'SCREEN_SHARE_STOPPED') {
          setProctorBlocked(true);
          setProctorWarning('屏幕共享已停止，正式面试不能继续作答。');
        } else if (proctorEvent.eventType === 'TAB_HIDDEN'
            || proctorEvent.eventType === 'WINDOW_BLUR') {
          setProctorWarning('已记录一次切屏或窗口失焦行为。');
        }
        try {
          await uploadProctorEventReliably(
            session.sessionId,
            proctorEvent.eventType,
            proctorEvent.evidence,
            proctorEvent.metadata,
            recordingStartedAtRef.current == null
              ? undefined
              : Math.max(0, Date.now() - recordingStartedAtRef.current),
          );
        } catch (cause) {
          console.error('Interview proctor event upload failed:', cause);
        }
      },
    ).catch(cause => {
      console.error('Interview proctor monitor failed:', cause);
      setProctorWarning('屏幕监考初始化失败，请检查屏幕共享后重新进入面试。');
    });
  };

  const handleDeviceStartFailed = async () => {
    visionMonitorRef.current?.stop();
    proctorMonitorRef.current?.stop();
    await Promise.allSettled([
      mediaRecorderRef.current?.stop() ?? Promise.resolve(),
      speechCaptureRef.current?.stop() ?? Promise.resolve(),
    ]);
    mediaRecorderRef.current = null;
    recordingStartedAtRef.current = null;
    speechCaptureRef.current = null;
    mediaStreamRef.current = null;
    screenStreamRef.current = null;
  };

  const prepareInterviewEntry = async () => {
    if (!session || !currentQuestion) {
      throw new Error('第一题尚未准备完成');
    }
    const socket = eventSocketRef.current;
    if (!socket) throw new Error('实时面试连接尚未创建');
    // 第一题已包含在创建好的会话中，不再重复请求当前题目。
    // 开始时会通过同一 WebSocket 依次发送 DEVICE_READY 和 START_INTERVIEW，
    // 因此这里不再额外调用 REST 设备确认接口。
    const [, nextChunkIndex] = await Promise.all([
      socket.waitUntilOpen(),
      resolveNextVideoChunkIndex(session.sessionId),
    ]);
    nextVideoChunkIndexRef.current = Math.max(
      nextVideoChunkIndexRef.current,
      nextChunkIndex,
    );
  };

  const prepareInterviewMedia = async (stream: MediaStream) => {
    try {
      prepareQuestionAudio();
      if (speechCaptureRef.current) return;
      const speechCapture = new InterviewSpeechCapture();
      speechCaptureRef.current = speechCapture;
      await speechCapture.start(stream, data => {
        eventSocketRef.current?.send('AUDIO_CHUNK', {data});
      });
    } catch (cause) {
      speechCaptureRef.current = null;
      console.error('Interview speech capture initialization failed:', cause);
      setRecordingWarning('实时语音识别暂时不可用，你仍可以使用文字回答。');
    }
  };

  const prepareQuestionAudio = () => {
    if (!audioContextRef.current) {
      audioContextRef.current = new AudioContext();
    }
    void audioContextRef.current.resume();
  };

  const playQuestionAudio = async (data: string, mimeType: string) => {
    void mimeType;
    prepareQuestionAudio();
    const audioContext = audioContextRef.current;
    if (!audioContext) return;
    try {
      questionAudioSourceRef.current?.stop();
    } catch {
      // 上一段题目音频已经结束。
    }
    speechCaptureRef.current?.setPaused(true);
    setReadingQuestion(true);
    try {
      await audioContext.resume();
      const binary = window.atob(data);
      const bytes = new Uint8Array(binary.length);
      for (let index = 0; index < binary.length; index++) {
        bytes[index] = binary.charCodeAt(index);
      }
      const buffer = await audioContext.decodeAudioData(bytes.buffer.slice(0));
      const source = audioContext.createBufferSource();
      source.buffer = buffer;
      source.connect(audioContext.destination);
      questionAudioSourceRef.current = source;
      await new Promise<void>(resolve => {
        source.addEventListener('ended', () => resolve(), {once: true});
        source.start();
      });
    } catch (cause) {
      console.error('Question audio playback failed:', cause);
      setError('AI 读题音频播放失败，请点击“重新播放题目”重试');
    } finally {
      setReadingQuestion(false);
      speechCaptureRef.current?.setPaused(answerModeRef.current !== 'voice');
    }
  };

  const handleAnswerModeChange = (mode: 'text' | 'voice') => {
    answerModeRef.current = mode;
    setAnswerMode(mode);
    setPartialTranscript('');
    speechCaptureRef.current?.setPaused(mode !== 'voice' || !speechReady || readingQuestion);
  };

  const readCurrentQuestion = () => {
    prepareQuestionAudio();
    if (!eventSocketRef.current?.send('READ_QUESTION')) {
      setError('AI 读题连接尚未就绪，请稍后重试');
    }
  };

  const restoreFullscreen = async () => {
    try {
      await document.documentElement.requestFullscreen();
      setProctorBlocked(false);
      setProctorWarning('');
    } catch {
      setProctorWarning('无法进入全屏，请允许浏览器全屏权限后重试。');
    }
  };

  const visionWarningMessage = (events: VisionEventType[]): string => {
    if (events.includes('CAMERA_INTERRUPTED')) return '摄像头已中断，请检查设备连接。';
    if (events.includes('MULTIPLE_FACES')) return '画面中检测到多人，请保持单人参加面试。';
    if (events.includes('FACE_MISSING')) return '画面中暂未检测到人脸，请调整坐姿或摄像头。';
    if (events.includes('LOW_LIGHT')) return '当前画面较暗，请改善环境光线。';
    return '';
  };

  const recoverLatestCreatedSession = async () => {
    for (let attempt = 0; attempt < 6; attempt++) {
      const sessions = await interviewApi.listSessions();
      const matched = sessions
        .filter(item => item.executionStatus === 'CREATED'
          || item.executionStatus === 'IN_PROGRESS')
        .filter(item => resumeId == null || item.resumeId === resumeId)
        .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())[0];
      if (matched) {
        return interviewApi.getSession(matched.sessionId);
      }
      await sleep(1500);
    }
    throw new Error('面试已创建但暂时无法恢复，请到面试记录中继续');
  };

  const handleSubmitAnswer = async () => {
    if (!answer.trim() || !session || !currentQuestion) return;

    setIsSubmitting(true);
    speechCaptureRef.current?.setPaused(true);
    setPartialTranscript('');

    const userMessage: Message = {
      type: 'user',
      content: answer
    };
    setMessages(prev => [...prev, userMessage]);

    if (jobId && eventSocketRef.current) {
      const started = eventSocketRef.current.send('ANSWER_STARTED');
      const submitted = started && eventSocketRef.current.send('ANSWER_SUBMITTED', {
        questionIndex: currentQuestion.questionIndex,
        answer: answer.trim(),
      });
      if (submitted) {
        setAnswer('');
        return;
      }
    }

    try {
      const response = await interviewApi.submitAnswer({
        sessionId: session.sessionId,
        questionIndex: currentQuestion.questionIndex,
        answer: answer.trim()
      });

      setAnswer('');

      if (response.hasNextQuestion && response.nextQuestion) {
        speechCaptureRef.current?.setPaused(answerModeRef.current !== 'voice');
        setCurrentQuestion(response.nextQuestion);
        setMessages(prev => response.nextQuestion!.isFollowUp ? [...prev, {
          type: 'interviewer',
          content: response.nextQuestion!.question,
          category: response.nextQuestion!.category,
          questionIndex: response.nextQuestion!.questionIndex
        }] : [{
          type: 'interviewer',
          content: response.nextQuestion!.question,
          category: response.nextQuestion!.category,
          questionIndex: response.nextQuestion!.questionIndex,
        }]);
      } else {
        void finalizeVideoRecording().finally(onInterviewComplete);
      }
    } catch (err) {
      setError('提交答案失败，请重试');
      console.error(err);
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleCompleteEarly = async () => {
    if (!session) return;

    setIsSubmitting(true);
    if (jobId && eventSocketRef.current?.send('FINISH_REQUEST')) {
      setShowCompleteConfirm(false);
      return;
    }
    try {
      await finalizeVideoRecording();
      await interviewApi.completeInterview(session.sessionId);
      setShowCompleteConfirm(false);
      onInterviewComplete();
    } catch (err) {
      setError('提前交卷失败，请重试');
      console.error(err);
    } finally {
      setIsSubmitting(false);
    }
  };

  if (officialInterview && !deviceReady && !error) {
    return (
      <InterviewDeviceCheck
        onStartRecording={handleRecordingStart}
        onReady={handleDeviceReady}
        onStartFailed={handleDeviceStartFailed}
        onPrepareInterview={prepareInterviewEntry}
        onPrepareMedia={prepareInterviewMedia}
        preparingInterview={isCreating
          || !session
          || session.questionPrepareStatus !== 'COMPLETED'}
      />
    );
  }

  // 非正式面试仍使用普通加载状态
  if (isCreating) {
    return (
      <div className="flex items-center justify-center min-h-[50vh]">
        <div className="w-full max-w-md text-center">
          <div className="w-10 h-10 border-3 border-slate-200 border-t-primary-500 rounded-full mx-auto mb-4 animate-spin" />
          <p className="text-slate-700 dark:text-slate-200 font-medium">正在生成 AI 面试题</p>
          <p className="mt-2 text-sm text-slate-500 dark:text-slate-400">{createStage}</p>
          <div className="mt-5 h-2 overflow-hidden rounded-full bg-slate-200 dark:bg-slate-700">
            <div
              className="h-full rounded-full bg-primary-500 transition-all duration-500"
              style={{width: `${createProgress}%`}}
            />
          </div>
          <div className="mt-2 flex items-center justify-between text-xs text-slate-500 dark:text-slate-400">
            <span>{createProgress}%</span>
            <span>已等待 {creatingElapsedSeconds}s</span>
          </div>
        </div>
      </div>
    );
  }

  // 错误状态
  if (error && !session) {
    return (
      <div className="flex items-center justify-center min-h-[50vh]">
        <div className="text-center">
          <p className="text-red-500 dark:text-red-400 mb-4">{error}</p>
          <div className="flex gap-3 justify-center">
            <button
              onClick={startInterview}
              className="px-5 py-2 bg-primary-500 text-white rounded-lg hover:bg-primary-600"
            >
              重试
            </button>
            <button
              onClick={onBack}
              className="px-5 py-2 bg-slate-200 dark:bg-slate-700 text-slate-700 dark:text-slate-300 rounded-lg hover:bg-slate-300 dark:hover:bg-slate-600"
            >
              返回
            </button>
          </div>
        </div>
      </div>
    );
  }

  if (!session || !currentQuestion) {
    return (
      <div className="flex items-center justify-center min-h-[50vh]">
        <div className="w-full max-w-md text-center">
          <p className="text-red-500 dark:text-red-400 mb-4">
            {error || '面试题目加载失败，请返回后重试'}
          </p>
          <div className="flex justify-center gap-3">
            {session?.questionPrepareStatus === 'FAILED' && (
              <button
                onClick={() => void retryQuestionPreparation()}
                disabled={isCreating}
                className="px-5 py-2 bg-primary-500 text-white rounded-lg hover:bg-primary-600 disabled:opacity-60"
              >
                {isCreating ? '正在重新准备…' : '重新准备题目'}
              </button>
            )}
            <button
              onClick={onBack}
              className="px-5 py-2 bg-slate-200 dark:bg-slate-700 text-slate-700 dark:text-slate-300 rounded-lg hover:bg-slate-300 dark:hover:bg-slate-600"
            >
              返回
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div>
      <motion.div
        key={currentQuestion.isFollowUp
          ? currentQuestion.parentQuestionIndex ?? currentQuestion.questionIndex
          : currentQuestion.questionIndex}
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ duration: 0.3 }}
      >
        {recordingWarning && (
          <div className="mb-4 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800 dark:border-amber-800 dark:bg-amber-950/30 dark:text-amber-200">
            {recordingWarning}
          </div>
        )}
        {visionWarning && (
          <div className="mb-4 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800 dark:border-amber-800 dark:bg-amber-950/30 dark:text-amber-200">
            {visionWarning}
          </div>
        )}
        {proctorWarning && (
          <div className="mb-4 flex items-center justify-between gap-4 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800 dark:border-red-800 dark:bg-red-950/30 dark:text-red-200">
            <span>{proctorWarning}</span>
            {proctorBlocked && document.fullscreenElement == null && (
              <button
                type="button"
                onClick={() => void restoreFullscreen()}
                className="shrink-0 rounded-lg bg-red-600 px-3 py-2 font-medium text-white"
              >
                重新进入全屏
              </button>
            )}
          </div>
        )}
        <InterviewChatPanel
          messages={messages}
          answer={answer}
          onAnswerChange={setAnswer}
          onSubmit={handleSubmitAnswer}
          onCompleteEarly={handleCompleteEarly}
          isSubmitting={isSubmitting || proctorBlocked}
          waitingForNextQuestion={waitingForNextQuestion}
          showCompleteConfirm={showCompleteConfirm}
          onShowCompleteConfirm={setShowCompleteConfirm}
          speechReady={speechReady}
          readingQuestion={readingQuestion}
          partialTranscript={partialTranscript}
          answerMode={answerMode}
          onAnswerModeChange={handleAnswerModeChange}
          onReadQuestion={readCurrentQuestion}
        />
      </motion.div>

      {/* 提前交卷确认对话框 */}
      <ConfirmDialog
        open={showCompleteConfirm}
        title="提前交卷"
        message="确定要提前交卷吗？未回答的问题将按0分计算。"
        confirmText="确定交卷"
        cancelText="取消"
        confirmVariant="warning"
        loading={isSubmitting}
        onConfirm={handleCompleteEarly}
        onCancel={() => setShowCompleteConfirm(false)}
      />
    </div>
  );
}
