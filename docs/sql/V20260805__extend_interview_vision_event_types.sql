-- 新增切屏、退出全屏、停止屏幕共享和屏幕采样事件后，
-- PostgreSQL 中旧的 Hibernate 枚举检查约束需要移除。
-- Java 枚举和接口参数仍会校验事件类型。

alter table interview_vision_events
  drop constraint if exists interview_vision_events_event_type_check;
