export interface VisionFrameSample {
  frame: Blob | null;
  brightness: number | null;
  cameraActive: boolean;
}

export class InterviewVisionMonitor {
  private timerId: number | null = null;
  private video: HTMLVideoElement | null = null;
  private videoTrack: MediaStreamTrack | null = null;
  private sampling = false;
  private onTrackEnded: (() => void) | null = null;
  private runId = 0;

  async start(
    stream: MediaStream,
    intervalMs: number,
    onSample: (sample: VisionFrameSample) => Promise<number | void>,
  ): Promise<void> {
    this.stop();
    const activeRunId = this.runId;
    const normalIntervalMs = this.normalizeInterval(intervalMs, 5_000);
    const videoTrack = stream.getVideoTracks()[0];
    if (!videoTrack) {
      await onSample({frame: null, brightness: null, cameraActive: false});
      return;
    }

    const video = document.createElement('video');
    video.muted = true;
    video.playsInline = true;
    video.srcObject = stream;
    this.video = video;
    this.videoTrack = videoTrack;
    this.onTrackEnded = () => {
      void onSample({frame: null, brightness: null, cameraActive: false});
    };
    videoTrack.addEventListener('ended', this.onTrackEnded);
    await video.play();

    const schedule = (delayMs: number) => {
      if (activeRunId !== this.runId) return;
      this.timerId = window.setTimeout(
        () => void capture(),
        this.normalizeInterval(delayMs, normalIntervalMs),
      );
    };

    const capture = async () => {
      if (activeRunId !== this.runId) return;
      if (this.sampling) return;
      if (videoTrack.readyState !== 'live') {
        await onSample({frame: null, brightness: null, cameraActive: false});
        return;
      }
      if (video.readyState < HTMLMediaElement.HAVE_CURRENT_DATA) {
        schedule(normalIntervalMs);
        return;
      }
      this.sampling = true;
      let nextIntervalMs = normalIntervalMs;
      try {
        const canvas = document.createElement('canvas');
        canvas.width = 640;
        canvas.height = 360;
        const context = canvas.getContext('2d', {willReadFrequently: true});
        if (!context) return;
        context.drawImage(video, 0, 0, canvas.width, canvas.height);
        const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
        let total = 0;
        let samples = 0;
        for (let index = 0; index < pixels.length; index += 64) {
          total += (pixels[index] + pixels[index + 1] + pixels[index + 2]) / 3;
          samples++;
        }
        const brightness = samples ? total / samples : 0;
        const frame = await new Promise<Blob | null>(resolve => {
          canvas.toBlob(resolve, 'image/jpeg', 0.75);
        });
        const recommendedIntervalMs = await onSample({
          frame,
          brightness,
          cameraActive: true,
        });
        if (typeof recommendedIntervalMs === 'number') {
          nextIntervalMs = recommendedIntervalMs;
        }
      } finally {
        this.sampling = false;
        schedule(nextIntervalMs);
      }
    };

    await capture();
  }

  stop(): void {
    this.runId++;
    if (this.timerId != null) window.clearTimeout(this.timerId);
    if (this.videoTrack && this.onTrackEnded) {
      this.videoTrack.removeEventListener('ended', this.onTrackEnded);
    }
    if (this.video) {
      this.video.pause();
      this.video.srcObject = null;
    }
    this.timerId = null;
    this.video = null;
    this.videoTrack = null;
    this.onTrackEnded = null;
    this.sampling = false;
  }

  private normalizeInterval(intervalMs: number, fallbackMs: number): number {
    if (!Number.isFinite(intervalMs) || intervalMs <= 0) return fallbackMs;
    return Math.min(60_000, Math.max(500, intervalMs));
  }
}
