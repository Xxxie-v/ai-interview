ALTER TABLE llm_global_setting
  ADD COLUMN IF NOT EXISTS question_generation_provider_id VARCHAR(64);

UPDATE llm_global_setting setting
SET question_generation_provider_id = CASE
  WHEN EXISTS (
    SELECT 1
    FROM llm_provider_config provider
    WHERE provider.id = 'dashscope-question'
  ) THEN 'dashscope-question'
  ELSE setting.default_chat_provider_id
END
WHERE setting.question_generation_provider_id IS NULL
   OR setting.question_generation_provider_id = '';
