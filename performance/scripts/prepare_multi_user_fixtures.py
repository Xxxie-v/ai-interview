#!/usr/bin/env python3
"""Prepare independent cloud candidates and interview sessions for k6."""

from __future__ import annotations

import argparse
import base64
import json
import os
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path


DEFAULT_BASE_URL = "https://ai-interview.xin"
DEFAULT_OUTPUT = "performance/k6/fixtures/multi-user-25.local.json"
RATE_LIMIT_CODE = 8001
RESUME_TEMPLATE = """AI应用研发工程师测试简历
姓名：压测候选人{index}
技能：Java、Spring Boot、Redis Stream、PostgreSQL、React、ONNX Runtime
项目：参与AI面试平台研发，实现异步任务、动态追问、视频上传与人脸识别。
经验：能够使用监控、日志、链路追踪和压力测试定位系统性能问题。
"""
ANSWERS = (
    "我会先建立延迟、错误率和吞吐量基线，再结合日志与链路追踪定位瓶颈。",
    "我会通过幂等键避免重复提交，并使用Redis Stream实现异步处理和失败重试。",
    "我会监控模型首字延迟、完整响应时间、超时率和限流次数，并设计降级策略。",
    "视频上传采用分片、校验和断点重传，元数据和对象存储状态需要保持一致。",
    "上线时采用灰度发布，持续观察CPU、内存、连接池、队列积压和业务错误率。",
)


class ApiClient:
  def __init__(self, base_url: str) -> None:
    self.base_url = base_url.rstrip("/")

  def json_request(
      self,
      method: str,
      path: str,
      payload: dict | None = None,
      token: str | None = None,
  ) -> dict:
    headers = {"Content-Type": "application/json"}
    if token:
      headers["Authorization"] = "Bearer " + token
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        self.base_url + path,
        data=body,
        headers=headers,
        method=method,
    )
    return self._open_json(request, f"{method} {path}")

  def upload_resume(self, token: str, filename: str, content: str) -> dict:
    boundary = "----k6fixture" + uuid.uuid4().hex
    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
        "Content-Type: text/plain; charset=utf-8\r\n\r\n"
    ).encode("utf-8")
    body += content.encode("utf-8")
    body += f"\r\n--{boundary}--\r\n".encode("utf-8")
    request = urllib.request.Request(
        self.base_url + "/api/resumes/upload",
        data=body,
        headers={
            "Authorization": "Bearer " + token,
            "Content-Type": "multipart/form-data; boundary=" + boundary,
        },
        method="POST",
    )
    return self._open_json(request, f"upload resume {filename}")

  def _open_json(self, request: urllib.request.Request, description: str) -> dict:
    for attempt in range(1, 6):
      try:
        with urllib.request.urlopen(request, timeout=60) as response:
          return json.loads(response.read().decode("utf-8"))
      except urllib.error.HTTPError:
        raise
      except (urllib.error.URLError, ConnectionError, TimeoutError, OSError) as error:
        if attempt == 5:
          raise RuntimeError(
              f"{description} failed after {attempt} network attempts") from error
        delay_seconds = min(2 ** (attempt - 1), 8)
        print(
            f"{description}: network error ({error}), retrying in "
            f"{delay_seconds} seconds",
            flush=True,
        )
        time.sleep(delay_seconds)


def require_success(result: dict, operation: str) -> dict:
  if result.get("code") != 200:
    raise RuntimeError(f"{operation} failed: {result.get('code')} {result.get('message')}")
  return result.get("data")


def retry_rate_limited(operation, description: str):
  for attempt in range(1, 7):
    result = operation()
    if result.get("code") != RATE_LIMIT_CODE:
      return result
    if attempt == 6:
      break
    print(f"{description}: rate limited, retrying after 1.2 seconds")
    time.sleep(1.2)
  return result


def register_or_login(
    client: ApiClient,
    username: str,
    password: str,
) -> tuple[str, bool]:
  registered = client.json_request(
      "POST",
      "/api/auth/register",
      {
          "username": username,
          "email": f"{username}@example.test",
          "password": password,
      },
  )
  if registered.get("code") == 200:
    return registered["data"]["accessToken"], True

  # A repeated run may intentionally reuse an existing prefix. Only then fall
  # back to login; new performance-test users no longer generate an expected
  # authentication failure before registration.
  login = client.json_request(
      "POST", "/api/auth/login", {"username": username, "password": password})
  data = require_success(login, f"login existing user {username}")
  return data["accessToken"], False


def find_or_upload_resume(
    client: ApiClient,
    token: str,
    username: str,
    index: int,
) -> tuple[int, bool]:
  resumes = require_success(
      client.json_request("GET", "/api/resumes", token=token),
      f"list resumes for {username}",
  )
  if resumes:
    return int(resumes[0]["id"]), False
  uploaded = retry_rate_limited(
      lambda: client.upload_resume(
          token,
          username + ".txt",
          RESUME_TEMPLATE.format(index=index),
      ),
      f"upload resume for {username}",
  )
  data = require_success(uploaded, f"upload resume for {username}")
  return int(data["resume"]["id"]), True


def find_or_create_session(
    client: ApiClient,
    token: str,
    username: str,
    resume_id: int,
    job_id: int,
) -> tuple[str, bool]:
  sessions = require_success(
      client.json_request("GET", "/api/interview/sessions", token=token),
      f"list sessions for {username}",
  )
  matching = [item for item in sessions if item.get("jobId") == job_id]
  if matching:
    session = matching[0]
    if session.get("executionStatus") not in ("CREATED", "IN_PROGRESS"):
      raise RuntimeError(
          f"{username} already completed job {job_id}; use a new --prefix")
    return session["sessionId"], False
  created = retry_rate_limited(
      lambda: client.json_request(
          "POST",
          "/api/interview/sessions",
          {
              "resumeText": "",
              "questionCount": 8,
              "resumeId": resume_id,
              "forceCreate": True,
              "skillId": "custom",
              "difficulty": "junior",
              "officialInterview": True,
              "jobId": job_id,
          },
          token,
      ),
      f"create session for {username}",
  )
  data = require_success(created, f"create session for {username}")
  return data["sessionId"], True


def assert_job_exists(client: ApiClient, token: str, job_id: int) -> None:
  jobs = require_success(
      client.json_request("GET", "/api/interviewee/jobs", token=token),
      "list active jobs",
  )
  if not any(job.get("id") == job_id for job in jobs):
    raise RuntimeError(f"active job {job_id} does not exist")


def wait_until_prepared(
    client: ApiClient,
    candidates: list[dict],
    tokens: dict[str, str],
    timeout_seconds: int,
) -> None:
  deadline = time.monotonic() + timeout_seconds
  while True:
    completed = 0
    processing = 0
    for candidate in candidates:
      username = candidate["username"]
      session_id = candidate["sessionId"]
      data = require_success(
          client.json_request(
              "GET",
              f"/api/interview/sessions/{session_id}",
              token=tokens[username],
          ),
          f"read session {session_id}",
      )
      status = data.get("questionPrepareStatus")
      if status == "FAILED":
        raise RuntimeError(
            f"question preparation failed for {session_id}: "
            f"{data.get('questionPrepareError')}")
      if status == "COMPLETED" and data.get("questions"):
        completed += 1
      elif status == "PROCESSING":
        processing += 1
    print(
        f"question preparation: {completed}/{len(candidates)} completed, "
        f"{processing} processing")
    if completed == len(candidates):
      return
    if time.monotonic() >= deadline:
      raise TimeoutError("question preparation did not finish before timeout")
    time.sleep(5)


def write_fixture(path: Path, candidates: list[dict]) -> None:
  if not path.name.endswith(".local.json"):
    raise ValueError("fixture filename must end with .local.json to stay ignored by Git")
  path.parent.mkdir(parents=True, exist_ok=True)
  path.write_text(
      json.dumps(candidates, ensure_ascii=False, indent=2) + "\n",
      encoding="utf-8",
  )


def parse_args() -> argparse.Namespace:
  parser = argparse.ArgumentParser()
  parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
  parser.add_argument("--count", type=int, default=25)
  parser.add_argument("--prefix", default="load25_0807")
  parser.add_argument("--job-id", type=int, default=1)
  parser.add_argument("--batch-size", type=int, default=5)
  parser.add_argument("--batch-pause", type=float, default=1.2)
  parser.add_argument("--prepare-timeout", type=int, default=600)
  parser.add_argument("--output", type=Path, default=Path(DEFAULT_OUTPUT))
  return parser.parse_args()


def main() -> None:
  args = parse_args()
  password = os.environ.get("PERF_FACTORY_PASSWORD")
  encoded_password = os.environ.get("PERF_FACTORY_PASSWORD_BASE64")
  if not password and encoded_password:
    password = base64.b64decode(encoded_password).decode("utf-8")
  if not password:
    raise RuntimeError(
        "PERF_FACTORY_PASSWORD or PERF_FACTORY_PASSWORD_BASE64 is required")
  if args.count < 1 or args.count > 100:
    raise ValueError("--count must be between 1 and 100")
  if args.batch_size < 1 or args.batch_size > 5:
    raise ValueError("--batch-size must be between 1 and 5")

  client = ApiClient(args.base_url)
  candidates: list[dict] = []
  tokens: dict[str, str] = {}
  for start in range(0, args.count, args.batch_size):
    stop = min(start + args.batch_size, args.count)
    for index in range(start + 1, stop + 1):
      username = f"{args.prefix}_{index:02d}"
      token, registered = register_or_login(client, username, password)
      tokens[username] = token
      if index == 1:
        assert_job_exists(client, token, args.job_id)
      resume_id, uploaded = find_or_upload_resume(
          client, token, username, index)
      session_id, created = find_or_create_session(
          client, token, username, resume_id, args.job_id)
      candidates.append({
          "username": username,
          "password": password,
          "sessionId": session_id,
          "answer": ANSWERS[(index - 1) % len(ANSWERS)],
      })
      print(
          f"candidate {index}/{args.count}: {username}, "
          f"registered={registered}, uploaded={uploaded}, sessionCreated={created}")
    if stop < args.count:
      time.sleep(args.batch_pause)

  wait_until_prepared(client, candidates, tokens, args.prepare_timeout)
  write_fixture(args.output, candidates)
  print(f"fixture created: {args.output} ({len(candidates)} candidates)")


if __name__ == "__main__":
  main()
