alter table interview_vision_events
  add column if not exists video_offset_ms bigint;

create index if not exists idx_vision_event_session_video_offset
  on interview_vision_events (session_id, video_offset_ms)
  where video_offset_ms is not null;
