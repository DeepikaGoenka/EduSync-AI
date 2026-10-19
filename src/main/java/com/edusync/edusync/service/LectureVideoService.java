package com.edusync.edusync.service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class LectureVideoService {

    // Same FFmpeg as your FFmpegService. Falls back to "ffmpeg" on PATH if this
    // file is missing.
    private static final String FFMPEG_WINGET = "C:\\Users\\Admin\\AppData\\Local\\Microsoft\\WinGet\\Packages\\"
            + "Gyan.FFmpeg.Shared_Microsoft.Winget.Source_8wekyb3d8bbwe\\"
            + "ffmpeg-9.0.2-full_build-shared\\bin\\ffmpeg.exe";

    // maxSlides is now a TARGET, not a hard cut: content is never dropped to hit
    // it.
    private static final int DEFAULT_MAX_SLIDES = 30;
    private static final int MAX_SLIDES_LIMIT = 60;

    // One model pass sees at most this many characters of notes (nothing is cut off
    // any more:
    // bigger topics are split into several parts, each with its own pass and
    // slide).
    private static final int MAX_UNIT_CHARS = 3000;
    // Topics smaller than this are merged with a neighbour from the same chapter.
    private static final int MIN_UNIT_CHARS = 350;

    private static final int MAX_BULLETS = 5;
    // Silence kept after the narration of each slide.
    private static final double TAIL_PAD_SEC = 0.35;

    // ---- heading detection (works for numbered notes like "5.4 Data Cube
    // Aggregation") ----
    // Page footer glued onto the end of a line, e.g. "...problems.Data Mining - MCA
    // 3rd Semester | 4"
    private static final Pattern FOOTER_GLUED = Pattern.compile(
            "\\b[A-Z][a-z]+(?: [A-Z][a-z]+)* [\u2013-] [A-Za-z0-9 ]{3,30} \\| \\d{1,3}\\b");
    private static final Pattern PAGE_NOISE = Pattern.compile("^.{0,60}\\|\\s*\\d{1,3}$");
    private static final Pattern TOC_LINE = Pattern.compile("^\\d{1,2}\\.\\s+\\d{1,2}\\.\\s+.*");
    private static final Pattern MAIN_HEADING = Pattern.compile("^(\\d{1,2})\\.\\s+([A-Z][A-Z0-9 &,()/'-]{3,80})$");
    private static final Pattern SUB_HEADING = Pattern.compile("^(\\d{1,2})\\.(\\d{1,2})\\.?\\s+([A-Z][^.!?]{2,90})$");
    private static final Pattern LETTER_HEADING = Pattern.compile("^[A-H]\\.\\s+([A-Z][^.!?]{2,60})$");

    private record Role(String name, String focus) {
    }

    // A chunk of notes about ONE topic. main = chapter number (0 if unknown).
    private record Unit(int main, String heading, String text) {
    }

    private record Head(int main, String title) {
    }

    private static final Role[] ROLES = {
            new Role("Introduction",
                    "Define the topic in simple words and explain why it matters."),
            new Role("Key concepts",
                    "List the main concepts, terms or components and say what each one means."),
            new Role("How it works",
                    "Explain how it works as a process. Put the steps of that process in the flow field."),
            new Role("Example",
                    "Give one concrete worked example taken from the notes, with realistic values."),
            new Role("Advantages and limitations",
                    "Give the advantages, the limitations, and points that are likely to be asked in an exam.")
    };

    private static class Job {
        final String id;
        final String topic;
        final boolean full;
        final long startedAt = System.currentTimeMillis();
        volatile String state = "QUEUED";
        volatile String message = "Waiting to start";
        volatile int progress = 0;
        volatile String videoPath = null;
        volatile String error = null;
        final List<String> slideTitles = new CopyOnWriteArrayList<>();

        Job(String id, String topic, boolean full) {
            this.id = id;
            this.topic = topic;
            this.full = full;
        }
    }

    private final AIService aiService;
    private final ContentService contentService;
    private final ChunkService chunkService;
    private final EmbeddingService embeddingService;
    private final LectureSlideRenderer renderer;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lecture-worker");
        t.setDaemon(true);
        return t;
    });

    private String cachedKey = "";
    private MaterialIndex cachedIndex = null;

    public LectureVideoService(
            AIService aiService,
            ContentService contentService,
            ChunkService chunkService,
            EmbeddingService embeddingService,
            LectureSlideRenderer renderer) {
        this.aiService = aiService;
        this.contentService = contentService;
        this.chunkService = chunkService;
        this.embeddingService = embeddingService;
        this.renderer = renderer;
    }

    // ---------- public API ----------

    // topic blank -> lecture on the WHOLE uploaded notes
    // topic given -> focused lecture on that topic
    public String start(String topic, Integer maxSlides) {
        String content = contentService.getLatestContent();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException(
                    "Please upload your study material first (POST /api/content/upload).");
        }

        boolean full = topic == null || topic.isBlank();
        int cap = maxSlides == null
                ? DEFAULT_MAX_SLIDES
                : Math.max(3, Math.min(MAX_SLIDES_LIMIT, maxSlides));

        String id = UUID.randomUUID().toString().substring(0, 8);
        Job job = new Job(id, full ? "Complete notes" : topic.trim(), full);
        jobs.put(id, job);
        executor.submit(() -> run(job, content, cap));
        return id;
    }

    public Map<String, Object> status(String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        Job j = jobs.get(id);
        if (j == null) {
            m.put("error", "Unknown job id: " + id);
            return m;
        }
        m.put("jobId", j.id);
        m.put("mode", j.full ? "WHOLE_NOTES" : "TOPIC");
        m.put("topic", j.topic);
        m.put("state", j.state);
        m.put("progress", j.progress);
        m.put("message", j.message);
        m.put("elapsedSeconds", (System.currentTimeMillis() - j.startedAt) / 1000);
        m.put("slides", new ArrayList<>(j.slideTitles));
        if ("DONE".equals(j.state)) {
            m.put("videoUrl", "/api/lecture/video/" + j.id);
            m.put("videoFile", j.videoPath);
        }
        if (j.error != null) {
            m.put("error", j.error);
        }
        return m;
    }

    public File videoFile(String id) {
        Job j = jobs.get(id);
        if (j == null || !"DONE".equals(j.state) || j.videoPath == null) {
            return null;
        }
        File f = new File(j.videoPath);
        return f.exists() ? f : null;
    }

    // ---------- the job ----------

    private void run(Job job, String content, int cap) {
        try {
            File dir = new File("generated-lectures", job.id);
            if (!dir.mkdirs() && !dir.exists()) {
                throw new IOException("Cannot create folder " + dir.getAbsolutePath());
            }

            List<LectureSlideSpec> body = new ArrayList<>();

            if (job.full) {
                update(job, "Splitting your notes into topics", 3);
                List<Unit> units = splitNotes(content, cap);
                System.out.println("[LECTURE " + job.id + "] topics=" + units.size());
                for (Unit u : units) {
                    System.out.println("[LECTURE " + job.id + "]   - " + u.heading()
                            + " (" + u.text().length() + " chars)");
                }

                for (int i = 0; i < units.size(); i++) {
                    update(job, "Writing slide " + (i + 1) + " of " + units.size()
                            + ": " + shorten(units.get(i).heading(), 40),
                            5 + (i * 43) / units.size());
                    body.add(buildSectionSlide(units.get(i), i + 1, units.size()));
                }
            } else {
                update(job, "Indexing your study material", 3);
                MaterialIndex index = getIndex(content);

                for (int i = 0; i < ROLES.length; i++) {
                    update(job, "Writing slide " + (i + 1) + " of " + ROLES.length
                            + ": " + ROLES[i].name(), 5 + (i * 43) / ROLES.length);
                    body.add(buildTopicSlide(job.topic, ROLES[i], index));
                }
            }

            update(job, "Writing the introduction and revision slides", 48);
            String opening = job.full ? openingText(content) : "";

            List<LectureSlideSpec> all = new ArrayList<>();
            all.add(introSlide(job.topic, body, job.full, opening));
            all.addAll(body);
            all.add(summarySlide(job.topic, body, job.full));
            for (LectureSlideSpec s : all) {
                job.slideTitles.add(s.title());
            }

            List<File> segments = new ArrayList<>();
            for (int i = 0; i < all.size(); i++) {
                update(job, "Recording and animating slide " + (i + 1) + " of " + all.size(),
                        50 + (i * 45) / all.size());
                segments.add(renderSegment(all.get(i), i + 1, all.size(), dir));
            }

            update(job, "Joining slides into the final video", 97);
            File out = new File(dir, "EduSync-Lecture.mp4");
            concatSegments(segments, out, dir);

            job.videoPath = out.getAbsolutePath();
            job.progress = 100;
            job.message = "Video is ready";
            job.state = "DONE";

        } catch (Exception e) {
            e.printStackTrace();
            job.error = e.getMessage() != null ? e.getMessage() : e.toString();
            job.message = "Failed";
            job.state = "FAILED";
        }
    }

    private void update(Job job, String message, int progress) {
        job.state = "RUNNING";
        job.message = message;
        job.progress = progress;
        System.out.println("[LECTURE " + job.id + "] " + progress + "% " + message);
    }

    // ---------- splitting the whole notes into topics ----------

    private List<String> cleanLines(String content) {
        String c = FOOTER_GLUED.matcher(content.replace("\r", "")).replaceAll("\n");
        List<String> lines = new ArrayList<>();

        for (String raw : c.split("\n")) {
            String l = raw.replace('\u00A0', ' ').replaceAll("[ \\t]+", " ").trim();
            if (l.isEmpty() || l.equals("Contents")) {
                continue;
            }
            if (PAGE_NOISE.matcher(l).matches() || TOC_LINE.matcher(l).matches()) {
                continue;
            }
            lines.add(l);
        }
        if (lines.isEmpty()) {
            throw new IllegalStateException("No usable text found in the study material.");
        }
        return lines;
    }

    private String openingText(String content) {
        String joined = String.join("\n", cleanLines(content));
        return joined.length() > 1500 ? joined.substring(0, 1500) : joined;
    }

    private List<Unit> splitNotes(String content, int cap) {
        List<String> lines = cleanLines(content);

        List<Unit> units = splitByHeadings(lines);
        if (units.size() < 2) {
            units = splitBySize(lines);
        }
        units = splitOversize(units);
        units = mergeSmall(units);
        units = mergeToCap(units, cap);

        // Exam question lists are not lecture content.
        List<Unit> kept = new ArrayList<>();
        for (Unit u : units) {
            if (!u.heading().toLowerCase(Locale.ROOT).contains("question")) {
                kept.add(u);
            }
        }
        return kept.size() >= 2 ? kept : units;
    }

    private Head headingOf(String l) {
        if (l.length() < 4 || l.length() > 95) {
            return null;
        }
        Matcher m = MAIN_HEADING.matcher(l);
        if (m.matches()) {
            return new Head(Integer.parseInt(m.group(1)), titleCase(m.group(2)));
        }
        m = SUB_HEADING.matcher(l);
        if (m.matches()) {
            return new Head(Integer.parseInt(m.group(1)), m.group(3).trim());
        }
        m = LETTER_HEADING.matcher(l);
        if (m.matches()) {
            return new Head(0, m.group(1).trim());
        }
        return null;
    }

    // One unit per heading. Heading-only lines (e.g. "1. DATA PRE-PROCESSING"
    // directly followed by
    // "1.1 Introduction") are kept with the next heading instead of becoming an
    // empty slide.
    private List<Unit> splitByHeadings(List<String> lines) {
        List<Unit> units = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int curMain = 0;
        String curHeading = "";
        int bodyChars = 0;

        for (String l : lines) {
            Head h = headingOf(l);
            if (h != null) {
                if (bodyChars >= 60 || (curHeading.isEmpty() && bodyChars > 0)) {
                    addUnit(units, curMain, curHeading, cur);
                    cur.setLength(0);
                    bodyChars = 0;
                }
                cur.append(l).append('\n');
                curHeading = h.title();
                if (h.main() > 0) {
                    curMain = h.main();
                }
            } else {
                cur.append(l).append('\n');
                bodyChars += l.length();
            }
        }
        addUnit(units, curMain, curHeading, cur);
        return units;
    }

    private void addUnit(List<Unit> units, int main, String heading, StringBuilder text) {
        String t = text.toString().trim();
        if (t.isEmpty()) {
            return;
        }
        // cover page / table of contents leftovers before the first heading
        if (heading.isEmpty() && t.length() < 600 && units.isEmpty()) {
            return;
        }
        units.add(new Unit(main, heading.isEmpty() ? firstLineTitle(t, "Introduction") : heading, t));
    }

    // Fallback for notes with no recognisable headings.
    private List<Unit> splitBySize(List<String> lines) {
        int total = 0;
        for (String l : lines) {
            total += l.length() + 1;
        }
        int n = Math.max(1, (int) Math.ceil(total / (MAX_UNIT_CHARS * 0.75)));
        double target = total / (double) n;

        List<Unit> units = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String l : lines) {
            if (cur.length() >= target && units.size() < n - 1) {
                String t = cur.toString().trim();
                units.add(new Unit(0, firstLineTitle(t, "Part " + (units.size() + 1)), t));
                cur.setLength(0);
            }
            cur.append(l).append('\n');
        }
        if (cur.toString().trim().length() > 0) {
            String t = cur.toString().trim();
            units.add(new Unit(0, firstLineTitle(t, "Part " + (units.size() + 1)), t));
        }
        return units;
    }

    // A topic bigger than one model pass is cut at line boundaries into equal
    // parts, so the
    // whole of it is covered (previously everything after ~3,600 chars was
    // ignored).
    private List<Unit> splitOversize(List<Unit> in) {
        List<Unit> out = new ArrayList<>();
        for (Unit u : in) {
            int len = u.text().length();
            if (len <= MAX_UNIT_CHARS) {
                out.add(u);
                continue;
            }
            int n = (int) Math.ceil(len / (double) MAX_UNIT_CHARS);
            double target = len / (double) n;

            List<String> parts = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            for (String line : u.text().split("\n")) {
                if (cur.length() >= target && parts.size() < n - 1) {
                    parts.add(cur.toString().trim());
                    cur.setLength(0);
                }
                cur.append(line).append('\n');
            }
            if (cur.toString().trim().length() > 0) {
                parts.add(cur.toString().trim());
            }
            for (int i = 0; i < parts.size(); i++) {
                out.add(new Unit(u.main(), u.heading() + " (part " + (i + 1) + " of " + parts.size() + ")",
                        parts.get(i)));
            }
        }
        return out;
    }

    private List<Unit> mergeSmall(List<Unit> in) {
        List<Unit> out = new ArrayList<>();
        for (Unit u : in) {
            if (!out.isEmpty()) {
                Unit prev = out.get(out.size() - 1);
                int a = prev.text().length();
                int b = u.text().length();
                boolean small = a < MIN_UNIT_CHARS || b < MIN_UNIT_CHARS;
                boolean related = prev.main() == u.main() || Math.min(a, b) < 250;
                if (small && related && a + b <= MAX_UNIT_CHARS) {
                    out.set(out.size() - 1, merge(prev, u));
                    continue;
                }
            }
            out.add(u);
        }
        return out;
    }

    // If there are more topics than the target, merge the smallest neighbours,
    // preferring ones
    // from the same chapter. Stops when merging would exceed one model pass.
    private List<Unit> mergeToCap(List<Unit> in, int cap) {
        List<Unit> u = new ArrayList<>(in);
        while (u.size() > cap) {
            int best = -1;
            long bestCost = Long.MAX_VALUE;
            for (int i = 0; i < u.size() - 1; i++) {
                Unit a = u.get(i);
                Unit b = u.get(i + 1);
                int sum = a.text().length() + b.text().length();
                if (sum > MAX_UNIT_CHARS) {
                    continue;
                }
                long cost = sum + (a.main() != b.main() ? 100000L : 0L);
                if (cost < bestCost) {
                    bestCost = cost;
                    best = i;
                }
            }
            if (best < 0) {
                break;
            }
            u.set(best, merge(u.get(best), u.get(best + 1)));
            u.remove(best + 1);
        }
        return u;
    }

    private Unit merge(Unit a, Unit b) {
        return new Unit(a.main(), shorten(a.heading() + " / " + b.heading(), 90),
                a.text() + "\n" + b.text());
    }

    private String titleCase(String s) {
        StringBuilder sb = new StringBuilder();
        for (String w : s.toLowerCase(Locale.ROOT).split(" ")) {
            if (w.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    // ---------- retrieval (topic mode only) ----------

    private synchronized MaterialIndex getIndex(String content) {
        String key = content.length() + ":" + content.hashCode();
        if (cachedIndex != null && key.equals(cachedKey)) {
            return cachedIndex;
        }

        List<String> chunks = new ArrayList<>();
        for (String c : chunkService.chunk(content, 900, 150)) {
            if (c != null && !c.isBlank()) {
                chunks.add(c);
            }
        }
        if (chunks.isEmpty()) {
            throw new IllegalStateException("No usable text found in the study material.");
        }

        List<String> docs = new ArrayList<>();
        for (String c : chunks) {
            docs.add("search_document: " + c);
        }

        cachedIndex = new MaterialIndex(chunks, embeddingService.embedAll(docs));
        cachedKey = key;
        return cachedIndex;
    }

    // ---------- slide writing: whole notes ----------

    private LectureSlideSpec buildSectionSlide(Unit unit, int no, int total) {

        String prompt = """
                You are a friendly university teacher preparing slide %d of %d of a lecture video
                that covers a student's complete notes. This slide is about ONE topic: "%s".

                Teach this topic properly, the way a good lecturer would in class. Use ONLY the SOURCE NOTES
                below. Do not invent facts. Keep the technical terms from the notes. Stay strictly on this
                topic and do not mention other topics that are not in the notes below.

                SOURCE NOTES:
                %s

                Return JSON only, in exactly this shape:
                {"title":"...","bullets":["...","...","..."],"narration":"...","flow":["...","..."],"takeaway":"..."}

                Rules:
                - title: at most 7 words, naming exactly this topic.
                - bullets: 4 or 5 bullets, each at most 18 words. Cover what matters in the notes:
                  the definition of the key term, the main types / methods / steps, a concrete example with
                  the real values from the notes, and advantages, limitations or exam points if present.
                  Do not copy whole sentences.
                - narration: 110 to 150 words, spoken in a natural teacher's voice. Explain every bullet in
                  your own words, walk through the example from the notes with its numbers, and add one simple
                  analogy if it helps. Do not read the bullets word by word. No lists, no symbols; write
                  formulas out in words.
                - flow: 3 to 5 very short step labels (at most 4 words each) ONLY if the notes describe an
                  ordered process or pipeline for THIS topic. Otherwise use an empty list.
                - takeaway: one sentence, at most 20 words.
                """.formatted(no, total, unit.heading(), unit.text());

        String fallbackTitle = shorten(unit.heading(), 50);

        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                LectureSlideSpec spec = parseSlide(aiService.askAIForJson(prompt), fallbackTitle);
                if (spec != null) {
                    return spec;
                }
                System.out.println("[LECTURE] unusable slide JSON, topic " + no + ", attempt " + attempt);
            } catch (Exception e) {
                System.out.println("[LECTURE] slide generation failed, topic " + no
                        + ", attempt " + attempt + ": " + e.getMessage());
            }
        }
        return fallbackFromText(fallbackTitle, unit.text(), "this topic");
    }

    private String firstLineTitle(String text, String dflt) {
        for (String line : text.split("\n")) {
            String t = clean(line).replaceFirst("^\\d+(?:\\.\\d+)*[.)]?\\s+", "");
            if (t.length() >= 4) {
                return shorten(t, 50);
            }
        }
        return dflt;
    }

    // ---------- slide writing: one topic ----------

    private LectureSlideSpec buildTopicSlide(String topic, Role role, MaterialIndex index) {

        double[] q = embeddingService.embed("search_query: " + topic + " " + role.name());
        List<MaterialIndex.Hit> hits = index.topK(q, 4);

        StringBuilder ctx = new StringBuilder();
        for (MaterialIndex.Hit h : hits) {
            String t = h.text().replaceAll("\\s+", " ").trim();
            if (t.length() > 900) {
                t = t.substring(0, 900);
            }
            ctx.append(t).append("\n\n");
        }

        String prompt = """
                You are a friendly university teacher preparing one slide of a lecture video on "%s".
                This slide is: %s. %s

                Use ONLY the SOURCE NOTES below. Do not invent facts. Keep the technical terms from the notes.

                SOURCE NOTES:
                %s

                Return JSON only, in exactly this shape:
                {"title":"...","bullets":["...","...","..."],"narration":"...","flow":["...","..."],"takeaway":"..."}

                Rules:
                - title: at most 7 words.
                - bullets: 4 or 5 bullets, each at most 18 words, including definitions and examples from the notes.
                  Do not copy whole sentences.
                - narration: 100 to 140 words, spoken in a natural teacher's voice, explaining the idea in your own words
                  with a simple analogy or example. Do not read the bullets word by word. No lists and no symbols.
                - flow: 3 to 5 very short step labels (at most 4 words each) ONLY if this slide describes a process
                  or a sequence. Otherwise use an empty list.
                - takeaway: one sentence, at most 20 words.
                """
                .formatted(topic, role.name(), role.focus(), ctx);

        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                LectureSlideSpec spec = parseSlide(aiService.askAIForJson(prompt), role.name());
                if (spec != null) {
                    return spec;
                }
                System.out.println("[LECTURE] unusable slide JSON, attempt " + attempt);
            } catch (Exception e) {
                System.out.println("[LECTURE] slide generation failed, attempt " + attempt
                        + ": " + e.getMessage());
            }
        }
        String best = hits.isEmpty() ? "" : hits.get(0).text();
        return fallbackFromText(role.name(), best, topic);
    }

    // ---------- parsing and fallbacks ----------

    private LectureSlideSpec parseSlide(String json, String defaultTitle) {
        try {
            JsonNode root = mapper.readTree(json);
            if (root.has("slide") && root.get("slide").isObject()) {
                root = root.get("slide");
            }

            String title = shorten(clean(root.path("title").asText("")), 60);
            if (title.isBlank()) {
                title = defaultTitle;
            }

            List<String> bullets = new ArrayList<>();
            for (JsonNode b : root.path("bullets")) {
                String t = shorten(clean(b.asText("")), 120);
                if (t.length() >= 4) {
                    bullets.add(t);
                }
                if (bullets.size() == MAX_BULLETS) {
                    break;
                }
            }
            if (bullets.size() < 3) {
                return null;
            }

            String narration = clean(root.path("narration").asText(""));
            if (narration.length() < 60) {
                narration = String.join(". ", bullets);
            }
            narration = limit(narration, 1400);

            List<String> flow = new ArrayList<>();
            for (JsonNode f : root.path("flow")) {
                String t = shorten(clean(f.asText("")), 28);
                if (t.length() >= 2) {
                    flow.add(t);
                }
                if (flow.size() == 5) {
                    break;
                }
            }
            if (flow.size() < 2) {
                flow.clear();
            }
            // The left column is narrower when a flow diagram is shown, so keep it to 4
            // bullets.
            if (!flow.isEmpty() && bullets.size() > 4) {
                bullets = new ArrayList<>(bullets.subList(0, 4));
            }

            String takeaway = shorten(clean(root.path("takeaway").asText("")), 150);
            if (takeaway.isBlank()) {
                takeaway = bullets.get(0);
            }

            return new LectureSlideSpec(title, bullets, narration, flow, takeaway);
        } catch (Exception e) {
            return null;
        }
    }

    // Used only if the model fails twice: build the slide straight from the notes
    // text.
    private LectureSlideSpec fallbackFromText(String title, String text, String label) {
        List<String> bullets = new ArrayList<>();
        List<String> spoken = new ArrayList<>();

        if (text != null && !text.isBlank()) {
            String flat = text.replaceAll("\\s+", " ").trim();
            for (String sentence : flat.split("(?<=[.!?])\\s+")) {
                String t = sentence.trim();
                if (t.length() >= 25 && t.length() <= 220) {
                    if (bullets.size() < MAX_BULLETS) {
                        bullets.add(shorten(t, 120));
                    }
                    if (spoken.size() < 6) {
                        spoken.add(t);
                    }
                }
            }
        }
        if (bullets.isEmpty()) {
            bullets.add("The notes do not explain " + label + " in detail");
            spoken.add("The uploaded notes do not explain " + label + " in detail.");
        }

        return new LectureSlideSpec(title, bullets, String.join(" ", spoken),
                new ArrayList<>(), bullets.get(0));
    }

    // Intro / summary slides written by the model so they say something specific.
    private LectureSlideSpec askFraming(String prompt, String defaultTitle) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                LectureSlideSpec spec = parseSlide(aiService.askAIForJson(prompt), defaultTitle);
                if (spec != null) {
                    return new LectureSlideSpec(spec.title(), spec.bullets(), spec.narration(),
                            new ArrayList<>(), spec.takeaway());
                }
            } catch (Exception e) {
                System.out.println("[LECTURE] intro/summary generation failed, attempt "
                        + attempt + ": " + e.getMessage());
            }
        }
        return null;
    }

    private LectureSlideSpec introSlide(String topic, List<LectureSlideSpec> body, boolean full,
            String opening) {
        LectureSlideSpec fallback = introFallback(topic, body, full);

        StringBuilder list = new StringBuilder();
        for (int i = 0; i < body.size(); i++) {
            list.append(i + 1).append(". ").append(body.get(i).title()).append('\n');
        }
        String subject = full ? "the student's complete study notes" : "the topic \"" + topic + "\"";

        String prompt = """
                You are a friendly university teacher opening a lecture video on %s.
                The lecture has these slides, in order:
                %s
                How the notes begin (for context only):
                %s

                Return JSON only, in exactly this shape:
                {"title":"...","bullets":["...","...","..."],"narration":"...","flow":[],"takeaway":"..."}

                Rules:
                - title: the subject of the lecture, at most 6 words (for example the course or subject name).
                - bullets: 4 or 5 bullets. Each names one major part of the lecture and says in a few words
                  what it covers. At most 16 words each. Group the slides above into major parts.
                - narration: 90 to 120 words. Welcome the student, name the subject, then walk through what
                  will be covered, in order, in plain words. End with "Let us begin." No lists, no symbols.
                - flow: always an empty list.
                - takeaway: one sentence, at most 20 words, saying what the student will be able to explain
                  or do after this lecture.
                """.formatted(subject, list, opening.isBlank() ? "(not available)" : opening);

        LectureSlideSpec s = askFraming(prompt, fallback.title());
        return s != null ? s : fallback;
    }

    private LectureSlideSpec introFallback(String topic, List<LectureSlideSpec> body, boolean full) {
        List<String> titles = new ArrayList<>();
        for (LectureSlideSpec s : body) {
            titles.add(s.title());
        }

        List<String> shown = new ArrayList<>();
        for (int i = 0; i < titles.size() && i < MAX_BULLETS; i++) {
            shown.add(titles.get(i));
        }
        if (titles.size() > MAX_BULLETS) {
            shown.set(MAX_BULLETS - 1, "...and " + (titles.size() - (MAX_BULLETS - 1)) + " more topics");
        }

        String narration;
        String heading;
        if (full) {
            heading = "Complete Lecture";
            narration = "Welcome to this lecture. We will go through your complete notes in "
                    + titles.size() + " parts, starting with " + titles.get(0)
                    + " and ending with " + titles.get(titles.size() - 1)
                    + ". Everything here is based on your own study notes. Let us begin.";
        } else {
            heading = shorten(topic, 60);
            narration = "Welcome to this lecture on " + topic + ". Today we will go through "
                    + joinNatural(titles) + ". Everything here is based on your own study notes. Let us begin.";
        }

        return new LectureSlideSpec(heading, shown, narration, new ArrayList<>(),
                "You will be able to explain each of these topics in your own words.");
    }

    private LectureSlideSpec summarySlide(String topic, List<LectureSlideSpec> body, boolean full) {
        LectureSlideSpec fallback = summaryFallback(topic, body, full);

        StringBuilder list = new StringBuilder();
        for (int i = 0; i < body.size(); i++) {
            list.append(i + 1).append(". ").append(body.get(i).title())
                    .append(": ").append(body.get(i).takeaway()).append('\n');
        }

        String prompt = """
                You are a friendly university teacher ending a lecture video with a quick revision.
                Here is what each slide of the lecture taught (title: key point):
                %s

                Return JSON only, in exactly this shape:
                {"title":"Quick Revision","bullets":["...","...","..."],"narration":"...","flow":[],"takeaway":"..."}

                Rules:
                - bullets: 5 bullets, each at most 18 words, together covering the WHOLE lecture from first
                  to last slide, one bullet per major part.
                - narration: 110 to 140 words. Recap those major parts in order in plain spoken words, then
                  wish the student good luck. No lists, no symbols.
                - flow: always an empty list.
                - takeaway: one exam tip, at most 20 words.
                """.formatted(list);

        LectureSlideSpec s = askFraming(prompt, "Quick Revision");
        return s != null ? s : fallback;
    }

    private LectureSlideSpec summaryFallback(String topic, List<LectureSlideSpec> body, boolean full) {
        List<String> points = new ArrayList<>();
        int n = body.size();
        int want = Math.min(n, MAX_BULLETS);

        for (int i = 0; i < want; i++) {
            int idx = want == 1 ? 0 : (int) Math.round(i * (n - 1) / (double) (want - 1));
            points.add(shorten(body.get(idx).takeaway(), 120));
        }

        String ending = full
                ? "That completes this lecture on your notes."
                : "That completes this lecture on " + topic + ".";
        String narration = "Let us quickly revise. " + String.join(". ", points)
                + ". " + ending + " Good luck with your revision.";

        return new LectureSlideSpec("Quick Revision", points, narration, new ArrayList<>(),
                "Revise these points before your exam.");
    }

    // ---------- audio, frames, ffmpeg ----------

    private File renderSegment(LectureSlideSpec spec, int slideNo, int total, File dir) throws Exception {

        File audio = new File(dir, "audio-" + slideNo + ".wav");
        String narration = speakable(spec.narration());
        runTts(narration, audio, dir, slideNo);
        double audioSec = audioSeconds(audio, narration);

        List<File> frames = renderer.renderFrames(spec, slideNo, total, dir);
        List<Double> durations = frameDurations(spec, audioSec);

        double totalSec = 0;
        for (double d : durations) {
            totalSec += d;
        }

        File segment = new File(dir, "segment-" + slideNo + ".mp4");
        buildSegment(frames, durations, totalSec, audio, segment, dir, slideNo);
        return segment;
    }

    // Timing: the text must be on screen BEFORE the voice reaches it. So the title
    // shows for a moment, all bullets are revealed during the first part of the
    // narration (never later than ~35% of it), and the full slide (flow diagram,
    // key idea) stays for the rest while the teacher keeps explaining.
    private List<Double> frameDurations(LectureSlideSpec spec, double audioSec) {
        int n = spec.bullets().size();
        double total = audioSec + TAIL_PAD_SEC;

        double tTitle = 0.4;
        double reveal = Math.min(total * 0.35, 14.0);
        reveal = Math.max(reveal, n * 0.9);
        double tFinal = total - tTitle - reveal;
        if (tFinal < 1.5) {
            tFinal = 1.5;
            reveal = Math.max(n * 0.6, total - tTitle - tFinal);
        }

        double sumLen = 0;
        for (String b : spec.bullets()) {
            sumLen += b.length() + 12;
        }

        List<Double> d = new ArrayList<>();
        d.add(tTitle);
        for (String b : spec.bullets()) {
            d.add(reveal * (b.length() + 12) / sumLen);
        }
        d.add(tFinal);
        return d;
    }

    private List<String> flowFromNotes(String text) {
        String best = null;
        int bestCount = 0;
        for (String line : text.split("\n")) {
            int c = line.split("\u2192", -1).length - 1;
            if (c > bestCount) {
                bestCount = c;
                best = line;
            }
        }
        List<String> flow = new ArrayList<>();
        if (best == null || bestCount < 2) {
            return flow;
        }
        for (String p : best.split("\u2192")) {
            String t = clean(p);
            if (t.length() >= 2 && t.length() <= 40) {
                flow.add(t.equals(t.toUpperCase()) ? titleCase(t) : t);
            }
        }
        return (flow.size() >= 3 && flow.size() <= 5) ? flow : new ArrayList<>();
    }

    // tts.py reads the text from a UTF-8 file and writes <base>.mp3 (edge-tts) or
    // <base>.wav (pyttsx3).
    // The result is then cleaned with ffmpeg: leading/trailing silence trimmed,
    // long pauses shortened.
    private void runTts(String text, File out, File dir, int slideNo) throws Exception {
        File txt = new File(dir, "narration-" + slideNo + ".txt");
        Files.writeString(txt.toPath(), text, StandardCharsets.UTF_8);

        File base = new File(dir, "raw-" + slideNo);
        new File(base.getAbsolutePath() + ".mp3").delete();
        new File(base.getAbsolutePath() + ".wav").delete();

        ProcessBuilder pb = new ProcessBuilder("python", "tts.py",
                txt.getAbsolutePath(), base.getAbsolutePath());
        pb.inheritIO();
        Process p = pb.start();
        if (!p.waitFor(180, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("Text-to-speech timed out.");
        }

        File raw = new File(base.getAbsolutePath() + ".mp3");
        if (!raw.exists() || raw.length() == 0) {
            raw = new File(base.getAbsolutePath() + ".wav");
        }
        if (p.exitValue() != 0 || !raw.exists() || raw.length() == 0) {
            throw new IOException("Text-to-speech failed. Check that 'python tts.py' works.");
        }

        cleanAudio(raw, out);
    }

    private void cleanAudio(File raw, File out) throws Exception {
        String af = "silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.05,"
                + "silenceremove=stop_periods=-1:stop_duration=0.9:stop_threshold=-45dB:stop_silence=0.35";
        try {
            runProcess(List.of(ffmpeg(), "-y", "-loglevel", "error",
                    "-i", raw.getAbsolutePath(),
                    "-af", af, "-ar", "44100", "-ac", "1",
                    out.getAbsolutePath()), 120, "FFmpeg (audio clean)");
        } catch (Exception e) {
            System.out.println("[LECTURE] silence trimming failed, converting without it: " + e.getMessage());
            runProcess(List.of(ffmpeg(), "-y", "-loglevel", "error",
                    "-i", raw.getAbsolutePath(),
                    "-ar", "44100", "-ac", "1",
                    out.getAbsolutePath()), 120, "FFmpeg (audio convert)");
        }
        if (!out.exists() || out.length() == 0) {
            throw new IOException("Audio conversion produced no file.");
        }
    }

    private double audioSeconds(File wav, String narration) {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(wav)) {
            double d = in.getFrameLength() / (double) in.getFormat().getFrameRate();
            if (d > 0.5) {
                return d;
            }
        } catch (Exception ignored) {
            // fall through to an estimate
        }
        int words = narration.trim().split("\\s+").length;
        return Math.max(3.0, words / 2.4);
    }

    private void buildSegment(List<File> frames, List<Double> durations, double totalSec,
            File audio, File out, File dir, int slideNo) throws Exception {

        File list = new File(dir, "frames-" + slideNo + ".txt");
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < frames.size(); i++) {
            lines.add("file '" + path(frames.get(i)) + "'");
            lines.add("duration " + String.format(Locale.ROOT, "%.3f", durations.get(i)));
        }
        lines.add("file '" + path(frames.get(frames.size() - 1)) + "'");
        Files.write(list.toPath(), lines);

        String vf = String.format(Locale.ROOT,
                "fps=25,scale=1280:720,format=yuv420p,fade=t=in:st=0:d=0.3,fade=t=out:st=%.3f:d=0.3",
                Math.max(0.5, totalSec - 0.3));

        // apad + -t: audio is padded with silence and cut at exactly the video length.
        List<String> cmd = List.of(
                ffmpeg(), "-y", "-loglevel", "error",
                "-f", "concat", "-safe", "0", "-i", list.getAbsolutePath(),
                "-i", audio.getAbsolutePath(),
                "-vf", vf,
                "-af", "apad",
                "-t", String.format(Locale.ROOT, "%.3f", totalSec),
                "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "128k", "-ar", "44100", "-ac", "1",
                "-movflags", "+faststart",
                out.getAbsolutePath());

        runProcess(cmd, 300, "FFmpeg (slide " + slideNo + ")");
    }

    private void concatSegments(List<File> segments, File out, File dir) throws Exception {
        File list = new File(dir, "concat.txt");
        List<String> lines = new ArrayList<>();
        for (File f : segments) {
            lines.add("file '" + path(f) + "'");
        }
        Files.write(list.toPath(), lines);

        List<String> cmd = List.of(
                ffmpeg(), "-y", "-loglevel", "error",
                "-f", "concat", "-safe", "0", "-i", list.getAbsolutePath(),
                "-c", "copy", "-movflags", "+faststart",
                out.getAbsolutePath());

        runProcess(cmd, 900, "FFmpeg (final video)");
    }

    private void runProcess(List<String> cmd, int timeoutSec, String what) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        Process p = pb.start();
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException(what + " timed out.");
        }
        if (p.exitValue() != 0) {
            throw new IOException(what + " failed (exit code " + p.exitValue() + ").");
        }
    }

    private String ffmpeg() {
        File f = new File(FFMPEG_WINGET);
        return f.exists() ? f.getAbsolutePath() : "ffmpeg";
    }

    private String path(File f) {
        return f.getAbsolutePath().replace("\\", "/");
    }

    // ---------- text helpers ----------

    private String clean(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("[*_#`]", "").replaceAll("\\s+", " ").trim();
    }

    private String shorten(String s, int max) {
        if (s == null || s.length() <= max) {
            return s == null ? "" : s;
        }
        int cut = s.lastIndexOf(' ', max - 3);
        if (cut < max / 2) {
            cut = max - 3;
        }
        return s.substring(0, cut).trim() + "...";
    }

    private String limit(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        int cut = s.lastIndexOf(". ", max);
        return cut > max / 2 ? s.substring(0, cut + 1) : s.substring(0, max);
    }

    // Plain text that the speech engine reads cleanly.
    private String speakable(String s) {
        String t = s == null ? "" : s;
        t = t.replace("\u2192", " then ").replace("->", " then ").replace("=>", " then ")
                .replace("&", " and ").replace("%", " percent ");
        t = t.replaceAll("[\"\u201c\u201d\\\\]", "");
        t = t.replaceAll("[^\\p{L}\\p{N} .,;:?!'()+/-]", " ");
        t = t.replaceAll("\\s+", " ").trim();
        if (!t.endsWith(".") && !t.endsWith("!") && !t.endsWith("?")) {
            t = t + ".";
        }
        return t;
    }

    private String joinNatural(List<String> items) {
        if (items.isEmpty()) {
            return "the main ideas";
        }
        if (items.size() == 1) {
            return items.get(0);
        }
        return String.join(", ", items.subList(0, items.size() - 1))
                + " and " + items.get(items.size() - 1);
    }
}