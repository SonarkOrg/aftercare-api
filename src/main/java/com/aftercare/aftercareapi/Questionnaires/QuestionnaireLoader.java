package com.aftercare.aftercareapi.Questionnaires;

import com.aftercare.aftercareapi.Enums.AppointmentType;
import com.aftercare.aftercareapi.Enums.QuestionnairePurpose;
import com.aftercare.aftercareapi.Enums.Specialty;
import com.aftercare.aftercareapi.Models.QuestionnaireDefinition;
import com.aftercare.aftercareapi.Repositories.QuestionnaireDefinitionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Loads questionnaire definitions from the classpath at startup (all profiles). Definitions are immutable per (url, version). */
@Component
@Order(1)
public class QuestionnaireLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(QuestionnaireLoader.class);
    private static final Set<String> WRAPPER_KEYS = Set.of("purpose", "specialty", "appointmentTypes", "questionnaire", "rules");

    private final QuestionnaireDefinitionRepository repository;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String location;
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    private final ObjectMapper mapper = new ObjectMapper();

    public QuestionnaireLoader(QuestionnaireDefinitionRepository repository, PlatformTransactionManager txManager,
                               Clock clock,
                               @Value("${aftercare.questionnaires.location:classpath:questionnaires/*.json}") String location) {
        this.repository = repository;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.location = location;
    }

    @Override
    public void run(ApplicationArguments args) {
        load(location);
    }

    /** Parses every matching file (any error aborts), then inserts new definitions and syncs status only. */
    public void load(String locationPattern) {
        List<Resource> resources = new ArrayList<>();
        try {
            for (Resource r : resolver.getResources(locationPattern)) {
                resources.add(r);
            }
        } catch (IOException e) {
            throw new InvalidQuestionnaireException(locationPattern + ": cannot list questionnaire files");
        }
        resources.sort(Comparator.comparing(r -> String.valueOf(r.getFilename())));

        List<QuestionnaireDefinition> parsed = new ArrayList<>();
        for (Resource r : resources) {
            String name = String.valueOf(r.getFilename());
            try {
                parsed.add(parseFile(r));
            } catch (InvalidQuestionnaireException e) {
                throw new InvalidQuestionnaireException(name + ": " + e.getMessage());
            } catch (IOException e) {
                throw new InvalidQuestionnaireException(name + ": not readable JSON");
            }
        }
        tx.executeWithoutResult(status -> parsed.forEach(this::upsert));
        log.info("Questionnaire definitions processed: {} file(s)", parsed.size());
    }

    private void upsert(QuestionnaireDefinition d) {
        Optional<QuestionnaireDefinition> existing = repository.findByUrlAndVersion(d.getUrl(), d.getVersion());
        if (existing.isEmpty()) {
            repository.save(d);
            return;
        }
        QuestionnaireDefinition stored = existing.get();
        if (!stored.getContentHash().equals(d.getContentHash())) {
            log.warn("Questionnaire {}|{} differs from stored copy; stored copy kept. Bump the version to change a form.",
                    d.getUrl(), d.getVersion());
        }
        if (stored.getStatus() != d.getStatus()) {
            stored.setStatus(d.getStatus());
            repository.save(stored);
        }
    }

    private QuestionnaireDefinition parseFile(Resource r) throws IOException {
        JsonNode root;
        try (InputStream in = r.getInputStream()) {
            root = mapper.readTree(in);
        } catch (JsonProcessingException e) {
            throw new InvalidQuestionnaireException("not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new InvalidQuestionnaireException("wrapper must be an object");
        }
        Iterator<String> names = root.fieldNames();
        while (names.hasNext()) {
            String k = names.next();
            if (!WRAPPER_KEYS.contains(k)) {
                throw new InvalidQuestionnaireException("unsupported wrapper element " + k);
            }
        }
        QuestionnairePurpose purpose;
        try {
            purpose = QuestionnairePurpose.valueOf(root.path("purpose").asText(""));
        } catch (IllegalArgumentException e) {
            throw new InvalidQuestionnaireException("purpose must be FOLLOW_UP_CHECKIN or PRE_APPOINTMENT");
        }
        Specialty specialty = null;
        JsonNode sp = root.get("specialty");
        if (sp != null && !sp.isNull()) {
            try {
                specialty = Specialty.valueOf(sp.asText());
            } catch (IllegalArgumentException e) {
                throw new InvalidQuestionnaireException("unknown specialty");
            }
        }
        Set<AppointmentType> types = EnumSet.noneOf(AppointmentType.class);
        JsonNode at = root.get("appointmentTypes");
        if (at == null || !at.isArray()) {
            throw new InvalidQuestionnaireException("appointmentTypes must be an array");
        }
        for (JsonNode t : at) {
            try {
                types.add(AppointmentType.valueOf(t.asText()));
            } catch (IllegalArgumentException e) {
                throw new InvalidQuestionnaireException("unknown appointment type");
            }
        }
        if (purpose == QuestionnairePurpose.FOLLOW_UP_CHECKIN && (specialty == null || !types.isEmpty())) {
            throw new InvalidQuestionnaireException("FOLLOW_UP_CHECKIN requires a specialty and no appointmentTypes");
        }
        if (purpose == QuestionnairePurpose.PRE_APPOINTMENT && types.isEmpty()) {
            throw new InvalidQuestionnaireException("PRE_APPOINTMENT requires appointmentTypes");
        }

        JsonNode qNode = root.get("questionnaire");
        ParsedQuestionnaire q = QuestionnaireParser.parse(qNode);
        JsonNode rulesNode = root.get("rules");
        RuleParser.parse(rulesNode, q, purpose);

        String fhirJson = write(qNode);
        String rulesJson = write(rulesNode);
        return QuestionnaireDefinition.builder()
                .url(q.url())
                .version(q.version())
                .titleFr(q.titleFr())
                .titleEn(q.titleEn())
                .purpose(purpose)
                .specialty(specialty)
                .appointmentTypes(new HashSet<>(types))
                .status(q.status())
                .fhirJson(fhirJson)
                .rulesJson(rulesJson)
                .contentHash(sha256(fhirJson + "\n" + rulesJson))
                .loadedAt(Instant.now(clock))
                .build();
    }

    private String write(JsonNode n) {
        try {
            return mapper.writeValueAsString(n);
        } catch (JsonProcessingException e) {
            throw new InvalidQuestionnaireException("cannot serialize JSON");
        }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
