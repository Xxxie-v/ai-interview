alter table interview_sessions
  add column if not exists question_prepare_status varchar(20),
  add column if not exists question_prepare_error varchar(500),
  add column if not exists question_prepared_at timestamp,
  add column if not exists question_prepare_updated_at timestamp;

update interview_sessions
set question_prepare_status = case
      when questions_json is not null and questions_json <> '' and questions_json <> '[]'
        then 'COMPLETED'
      else 'PENDING'
    end,
    question_prepared_at = case
      when questions_json is not null and questions_json <> '' and questions_json <> '[]'
        then coalesce(completed_at, created_at)
      else null
    end,
    question_prepare_updated_at = coalesce(completed_at, created_at, current_timestamp)
where question_prepare_status is null
   or (question_prepare_status <> 'COMPLETED'
       and questions_json is not null
       and questions_json <> ''
       and questions_json <> '[]');

alter table interview_sessions
  alter column question_prepare_status set default 'PENDING',
  alter column question_prepare_status set not null;

create index if not exists idx_interview_question_prepare_recovery
  on interview_sessions (question_prepare_status, question_prepare_updated_at)
  where status = 'CREATED';
