export interface RecordedVideoChunk {
  index: number;
  durationMs: number;
  blob: Blob;
}

export class InterviewMediaRecorder {
  private recorder: MediaRecorder | null = null;
  private uploadQueue: Promise<void> = Promise.resolve();
  private chunkIndex = 0;
  private lastChunkAt = 0;
  private stopPromise: Promise<void> | null = null;

  start(
    stream: MediaStream,
    chunkDurationMs: number,
    initialChunkIndex: number,
    onChunk: (chunk: RecordedVideoChunk) => Promise<void>,
  ): void {
    if (this.recorder && this.recorder.state !== 'inactive') return;
    const mimeType = this.resolveMimeType();
    this.recorder = mimeType
      ? new MediaRecorder(stream, {mimeType})
      : new MediaRecorder(stream);
    this.chunkIndex = Math.max(0, initialChunkIndex);
    this.lastChunkAt = Date.now();
    this.uploadQueue = Promise.resolve();

    this.recorder.addEventListener('dataavailable', event => {
      if (!event.data.size) return;
      const now = Date.now();
      const chunk: RecordedVideoChunk = {
        index: this.chunkIndex++,
        durationMs: Math.max(1, now - this.lastChunkAt),
        blob: event.data,
      };
      this.lastChunkAt = now;
      this.uploadQueue = this.uploadQueue.then(() => onChunk(chunk));
    });
    this.recorder.start(chunkDurationMs);
  }

  async stop(): Promise<void> {
    if (this.stopPromise) return this.stopPromise;
    const recorder = this.recorder;
    if (!recorder || recorder.state === 'inactive') {
      await this.uploadQueue;
      return;
    }
    this.stopPromise = new Promise<void>((resolve, reject) => {
      recorder.addEventListener('stop', () => {
        this.uploadQueue.then(resolve).catch(reject);
      }, {once: true});
      recorder.stop();
    }).finally(() => {
      this.recorder = null;
      this.stopPromise = null;
    });
    return this.stopPromise;
  }

  private resolveMimeType(): string {
    const supported = [
      'video/webm;codecs=vp9,opus',
      'video/webm;codecs=vp8,opus',
      'video/webm',
      'video/mp4',
    ];
    return supported.find(type => MediaRecorder.isTypeSupported(type)) || '';
  }
}
