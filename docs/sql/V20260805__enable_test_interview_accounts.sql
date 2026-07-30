-- 允许被管理员标记为测试候选人的账号重复参加同一岗位面试。
-- 普通账号的一岗一次限制仍由 InterviewSessionService 在 Redis 分布式锁内执行。

alter table interview_sessions
  drop constraint if exists uk_interview_session_owner_job;

create index if not exists idx_interview_session_owner_job
  on interview_sessions (owner_user_id, job_id);
