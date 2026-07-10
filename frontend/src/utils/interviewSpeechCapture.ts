export class InterviewSpeechCapture {
  private audioContext: AudioContext | null = null;
  private processor: ScriptProcessorNode | null = null;
  private source: MediaStreamAudioSourceNode | null = null;
  private sink: GainNode | null = null;
  private paused = true;

  async start(stream: MediaStream, onAudioData: (base64Pcm: string) => void): Promise<void> {
    if (this.audioContext) return;
    const audioContext = new AudioContext();
    await audioContext.resume();
    const source = audioContext.createMediaStreamSource(stream);
    const processor = audioContext.createScriptProcessor(4096, 1, 1);
    const sink = audioContext.createGain();
    sink.gain.value = 0;
    const targetSampleRate = 16_000;
    const samplesPerChunk = targetSampleRate / 5;
    let pending = new Int16Array(0);

    processor.onaudioprocess = event => {
      if (this.paused) return;
      const input = event.inputBuffer.getChannelData(0);
      const ratio = audioContext.sampleRate / targetSampleRate;
      const outputLength = Math.max(1, Math.round(input.length / ratio));
      const pcm = new Int16Array(outputLength);
      for (let index = 0; index < outputLength; index++) {
        const sourceIndex = index * ratio;
        const lower = Math.floor(sourceIndex);
        const upper = Math.min(lower + 1, input.length - 1);
        const weight = sourceIndex - lower;
        const sample = Math.max(-1, Math.min(
          1,
          input[lower] * (1 - weight) + input[upper] * weight,
        ));
        pcm[index] = sample < 0 ? sample * 0x8000 : sample * 0x7fff;
      }
      const combined = new Int16Array(pending.length + pcm.length);
      combined.set(pending);
      combined.set(pcm, pending.length);
      let offset = 0;
      while (combined.length - offset >= samplesPerChunk) {
        const chunk = combined.subarray(offset, offset + samplesPerChunk);
        offset += samplesPerChunk;
        const bytes = new Uint8Array(chunk.buffer, chunk.byteOffset, chunk.byteLength);
        let binary = '';
        for (const byte of bytes) binary += String.fromCharCode(byte);
        onAudioData(window.btoa(binary));
      }
      pending = combined.slice(offset);
    };

    source.connect(processor);
    processor.connect(sink);
    sink.connect(audioContext.destination);
    this.audioContext = audioContext;
    this.processor = processor;
    this.source = source;
    this.sink = sink;
  }

  setPaused(paused: boolean): void {
    this.paused = paused;
  }

  async stop(): Promise<void> {
    this.paused = true;
    this.processor?.disconnect();
    this.source?.disconnect();
    this.sink?.disconnect();
    await this.audioContext?.close();
    this.audioContext = null;
    this.processor = null;
    this.source = null;
    this.sink = null;
  }
}
