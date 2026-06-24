export interface StorageInfo {
  fileKey: string;
  fileUrl: string;
  resumeId?: number;
}

export interface UploadResponse {
  resume?: {
    id: number;
    filename: string;
    questionPrepareStatus: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';
  };
  storage: StorageInfo;
  duplicate?: boolean;
  message?: string;
}

export interface ApiError {
  error: string;
  detectedType?: string;
  allowedTypes?: string[];
}
