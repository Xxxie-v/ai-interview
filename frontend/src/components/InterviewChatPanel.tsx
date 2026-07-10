import {useRef} from 'react';
import {motion} from 'framer-motion';
import {Virtuoso, type VirtuosoHandle} from 'react-virtuoso';
import {Keyboard, Loader2, Mic, Send, Volume2} from 'lucide-react';
import InterviewMessageBubble from './InterviewMessageBubble';

interface Message {
  type: 'interviewer' | 'user';
  content: string;
  category?: string;
  questionIndex?: number;
}

interface InterviewChatPanelProps {
  messages: Message[];
  answer: string;
  onAnswerChange: (answer: string) => void;
  onSubmit: () => void;
  onCompleteEarly: () => void;
  isSubmitting: boolean;
  waitingForNextQuestion?: boolean;
  showCompleteConfirm: boolean;
  onShowCompleteConfirm: (show: boolean) => void;
  speechReady?: boolean;
  readingQuestion?: boolean;
  partialTranscript?: string;
  answerMode: 'text' | 'voice';
  onAnswerModeChange: (mode: 'text' | 'voice') => void;
  onReadQuestion: () => void;
}

/**
 * 面试聊天面板组件
 */
export default function InterviewChatPanel({
  messages,
  answer,
  onAnswerChange,
  onSubmit,
  // onCompleteEarly, // 暂时未使用
  isSubmitting,
  waitingForNextQuestion = false,
  // showCompleteConfirm, // 暂时未使用
  onShowCompleteConfirm,
  speechReady = false,
  readingQuestion = false,
  partialTranscript = '',
  answerMode,
  onAnswerModeChange,
  onReadQuestion,
}: InterviewChatPanelProps) {
  const virtuosoRef = useRef<VirtuosoHandle>(null);

  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
      onSubmit();
    }
  };

  return (
    <div className="flex h-[calc(100vh-3rem)] max-w-5xl mx-auto flex-col">
      {/* 聊天区域 */}
        <div
            className="flex-1 bg-white dark:bg-slate-800 rounded-2xl shadow-sm dark:shadow-slate-900/50 overflow-hidden flex flex-col min-h-0 border border-slate-100 dark:border-slate-700">
        <Virtuoso
          ref={virtuosoRef}
          data={messages}
          initialTopMostItemIndex={messages.length - 1}
          followOutput="smooth"
          className="flex-1"
          itemContent={(_index, msg) => (
            <div className="pb-4 px-6 first:pt-6">
              <InterviewMessageBubble
                role={msg.type === 'interviewer' ? 'interviewer' : 'user'}
                text={msg.content}
                category={msg.category}
              />
            </div>
          )}
        />

        {/* 输入区域 */}
            <div className="border-t border-slate-200 dark:border-slate-600 p-4 bg-slate-50 dark:bg-slate-700/50">
          <div className="mb-3 flex flex-wrap items-center gap-2 text-xs">
            <button
              type="button"
              onClick={() => onAnswerModeChange('text')}
              className={`inline-flex items-center gap-1.5 rounded-lg px-3 py-2 font-medium ${
                answerMode === 'text'
                  ? 'bg-primary-500 text-white'
                  : 'bg-white text-slate-600 dark:bg-slate-800 dark:text-slate-300'
              }`}
            >
              <Keyboard className="h-3.5 w-3.5" />文字回答
            </button>
            <button
              type="button"
              onClick={() => onAnswerModeChange('voice')}
              disabled={!speechReady}
              className={`inline-flex items-center gap-1.5 rounded-lg px-3 py-2 font-medium disabled:cursor-not-allowed disabled:opacity-50 ${
                answerMode === 'voice'
                  ? 'bg-emerald-500 text-white'
                  : 'bg-white text-slate-600 dark:bg-slate-800 dark:text-slate-300'
              }`}
            >
              <Mic className="h-3.5 w-3.5" />
              {answerMode === 'voice' ? '正在听你回答' : '语音回答'}
            </button>
            <button
              type="button"
              onClick={onReadQuestion}
              disabled={readingQuestion}
              className="inline-flex items-center gap-1.5 rounded-lg bg-primary-100 px-3 py-2 font-medium text-primary-700 disabled:opacity-60"
            >
              <Volume2 className="h-3.5 w-3.5" />
              {readingQuestion ? 'AI 正在读题' : '重新播放题目'}
            </button>
            <span className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1 ${
              speechReady
                ? 'bg-emerald-100 text-emerald-700'
                : 'bg-slate-200 text-slate-500'
            }`}>
              <Mic className="h-3.5 w-3.5" />
              {speechReady ? '语音识别已就绪' : '语音识别连接中'}
            </span>
            {answerMode === 'voice' && partialTranscript && (
              <span className="text-slate-500">识别中：{partialTranscript}</span>
            )}
          </div>
          {waitingForNextQuestion && (
            <div className="mb-3 flex items-center gap-2 text-sm text-primary-600">
              <Loader2 className="h-4 w-4 animate-spin" />
              AI 正在分析回答并准备下一题…
            </div>
          )}
          <div className="flex gap-3">
            <textarea
              value={answer}
              onChange={(e) => onAnswerChange(e.target.value)}
              onKeyDown={handleKeyPress}
              placeholder={answerMode === 'voice'
                ? '正在将你的语音转换为文字，也可以在这里修改识别结果'
                : '输入你的回答... (Ctrl/Cmd + Enter 提交)'}
              className="flex-1 px-4 py-3 border border-slate-300 dark:border-slate-500 rounded-xl focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-transparent resize-none bg-white dark:bg-slate-800 text-slate-900 dark:text-white placeholder-slate-400 dark:placeholder-slate-500"
              rows={3}
              disabled={isSubmitting || waitingForNextQuestion}
            />
            <div className="flex flex-col gap-2">
              <motion.button
                onClick={onSubmit}
                disabled={!answer.trim() || isSubmitting || waitingForNextQuestion}
                className="px-6 py-3 bg-primary-500 text-white rounded-xl font-medium hover:bg-primary-600 transition-colors disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2"
                whileHover={{ scale: isSubmitting || waitingForNextQuestion || !answer.trim() ? 1 : 1.02 }}
                whileTap={{ scale: isSubmitting || waitingForNextQuestion || !answer.trim() ? 1 : 0.98 }}
              >
                {isSubmitting ? (
                  <>
                    <motion.div
                      className="w-4 h-4 border-2 border-white border-t-transparent rounded-full"
                      animate={{ rotate: 360 }}
                      transition={{ duration: 1, repeat: Infinity, ease: "linear" }}
                    />
                    提交中
                  </>
                ) : (
                  <>
                    <Send className="w-4 h-4" />
                    提交
                  </>
                )}
              </motion.button>
              <motion.button
                onClick={() => onShowCompleteConfirm(true)}
                disabled={isSubmitting}
                className="px-6 py-3 bg-slate-200 dark:bg-slate-600 text-slate-700 dark:text-slate-200 rounded-xl font-medium hover:bg-slate-300 dark:hover:bg-slate-500 transition-colors disabled:opacity-50 disabled:cursor-not-allowed text-sm"
                whileHover={{ scale: isSubmitting ? 1 : 1.02 }}
                whileTap={{ scale: isSubmitting ? 1 : 0.98 }}
              >
                提前交卷
              </motion.button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
