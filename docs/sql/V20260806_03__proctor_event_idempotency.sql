alter table interview_vision_events
  add column if not exists client_event_id varchar(36);

create unique index if not exists uidx_vision_event_session_client_event
  on interview_vision_events (session_id, client_event_id)
  where client_event_id is not null;
