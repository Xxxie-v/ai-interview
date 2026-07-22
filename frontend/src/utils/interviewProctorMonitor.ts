import type {VisionEventType} from '../api/interviewVision';

interface ProctorEvidence {
  eventType: VisionEventType;
  evidence?: Blob;
  metadata?: Record<string, unknown>;
}

export class InterviewProctorMonitor {
  private screenVideo: HTMLVideoElement | null = null;
  private screenTrack: MediaStreamTrack | null = null;
  private captureTimerId: number | null = null;
  private active = false;
  private lastEventAt = new Map<VisionEventType, number>();
  private onEvidence: ((event: ProctorEvidence) => Promise<void>) | null = null;

  async start(
    screenStream: MediaStream,
    captureIntervalMs: number,
    onEvidence: (event: ProctorEvidence) => Promise<void>,
  ): Promise<void> {
    this.stop();
    const screenTrack = screenStream.getVideoTracks()[0];
    if (!screenTrack) throw new Error('未获取到屏幕共享画面');

    this.active = true;
    this.screenTrack = screenTrack;
    this.onEvidence = onEvidence;
    this.screenVideo = document.createElement('video');
    this.screenVideo.muted = true;
    this.screenVideo.playsInline = true;
    this.screenVideo.srcObject = screenStream;
    screenTrack.addEventListener('ended', this.handleScreenShareStopped);
    document.addEventListener('visibilitychange', this.handleVisibilityChange);
    document.addEventListener('fullscreenchange', this.handleFullscreenChange);
    window.addEventListener('blur', this.handleWindowBlur);
    await this.screenVideo.play();

    await this.captureScreen();
    this.captureTimerId = window.setInterval(
      () => void this.captureScreen(),
      Math.max(10_000, captureIntervalMs),
    );
  }

  stop(): void {
    this.active = false;
    if (this.captureTimerId != null) window.clearInterval(this.captureTimerId);
    this.screenTrack?.removeEventListener('ended', this.handleScreenShareStopped);
    document.removeEventListener('visibilitychange', this.handleVisibilityChange);
    document.removeEventListener('fullscreenchange', this.handleFullscreenChange);
    window.removeEventListener('blur', this.handleWindowBlur);
    if (this.screenVideo) {
      this.screenVideo.pause();
      this.screenVideo.srcObject = null;
    }
    this.captureTimerId = null;
    this.screenTrack = null;
    this.screenVideo = null;
    this.onEvidence = null;
    this.lastEventAt.clear();
  }

  private readonly handleVisibilityChange = () => {
    if (document.hidden) void this.emitOnce('TAB_HIDDEN');
  };

  private readonly handleWindowBlur = () => {
    void this.emitOnce('WINDOW_BLUR');
  };

  private readonly handleFullscreenChange = () => {
    if (!document.fullscreenElement) void this.emitOnce('FULLSCREEN_EXIT');
  };

  private readonly handleScreenShareStopped = () => {
    void this.emitOnce('SCREEN_SHARE_STOPPED');
  };

  private async emitOnce(eventType: VisionEventType): Promise<void> {
    if (!this.active || !this.onEvidence) return;
    const now = Date.now();
    if (now - (this.lastEventAt.get(eventType) ?? 0) < 3_000) return;
    this.lastEventAt.set(eventType, now);
    await this.onEvidence({eventType, metadata: {capturedAt: new Date(now).toISOString()}});
  }

  private async captureScreen(): Promise<void> {
    const video = this.screenVideo;
    if (!this.active || !video || !this.onEvidence
        || video.readyState < HTMLMediaElement.HAVE_CURRENT_DATA) return;
    const sourceWidth = video.videoWidth || 1280;
    const sourceHeight = video.videoHeight || 720;
    const width = Math.min(1280, sourceWidth);
    const height = Math.max(1, Math.round(sourceHeight * (width / sourceWidth)));
    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    const context = canvas.getContext('2d');
    if (!context) return;
    context.drawImage(video, 0, 0, width, height);
    const evidence = await new Promise<Blob | null>(resolve => {
      canvas.toBlob(resolve, 'image/jpeg', 0.65);
    });
    if (!evidence) return;
    await this.onEvidence({
      eventType: 'SCREEN_CAPTURED',
      evidence,
      metadata: {
        width,
        height,
        displaySurface: this.screenTrack?.getSettings().displaySurface ?? 'unknown',
      },
    });
  }
}
