package interview.guide.modules.recruitment.service;

import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.interview.skill.InterviewSkillService.QuestionBankItem;
import interview.guide.modules.interview.service.InterviewQuestionProperties;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class JobQuestionBankService {

  private final InterviewSkillService skillService;
  private final InterviewQuestionProperties properties;
  private final SecureRandom random = new SecureRandom();

  public JobQuestionBankService(
      InterviewSkillService skillService,
      InterviewQuestionProperties properties) {
    this.skillService = skillService;
    this.properties = properties;
  }

  public List<InterviewQuestionDTO> selectFixedQuestions(
      String name,
      String description,
      String requirements) {
    String skillId = resolveSkillId(name + " " + description + " " + requirements);
    List<QuestionBankItem> bank = new ArrayList<>(skillService.loadQuestionBank(skillId));
    Collections.shuffle(bank, random);
    List<InterviewQuestionDTO> result = new ArrayList<>();
    int count = Math.max(3, Math.min(6, properties.getJobFixedQuestionCount()));
    for (int index = 0; index < Math.min(count, bank.size()); index++) {
      QuestionBankItem item = bank.get(index);
      result.add(InterviewQuestionDTO.create(
          index,
          item.question(),
          item.type(),
          item.category(),
          item.topicSummary(),
          false,
          null));
    }
    return result;
  }

  private String resolveSkillId(String jobText) {
    String normalized = jobText.toLowerCase(Locale.ROOT);
    if (containsAny(normalized, "python", "django", "flask")) return "python-backend";
    if (containsAny(normalized, "前端", "react", "vue", "javascript", "typescript")) {
      return "frontend";
    }
    if (containsAny(normalized, "agent", "大模型", "llm", "人工智能")) return "ai-agent-dev";
    if (containsAny(normalized, "算法", "数据结构")) return "algorithm";
    if (containsAny(normalized, "测试", "qa", "质量保障")) return "test-development";
    return "java-backend";
  }

  private boolean containsAny(String source, String... keywords) {
    for (String keyword : keywords) {
      if (source.contains(keyword)) return true;
    }
    return false;
  }
}
