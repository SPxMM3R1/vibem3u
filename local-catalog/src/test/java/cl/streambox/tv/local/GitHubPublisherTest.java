package cl.streambox.tv.local;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class GitHubPublisherTest {
    @Test
    public void staleEditorDocumentsRebaseOnTheNewestRemoteTreeAndStillCommit() throws Exception {
        FakeGitHub api = new FakeGitHub();
        GitHubPublisher publisher = new GitHubPublisher(api::execute);
        JSONObject request = new JSONObject()
                .put("documents", new JSONObject()
                        .put("data/channel-editor-layout.json", "{\"schemaVersion\":1}")
                        .put("data/vibem3u-selection.json", "{\"schemaVersion\":1}")
                        .put("presentation-overrides.json", "{\"schema\":1}"))
                // This intentionally stale preflight used to reject editor-owned
                // changes after the runner updated the same manifest.
                .put("expectedShas", new JSONObject().put("data/vibem3u-selection.json", "old-blob"));

        GitHubPublisher.PublishResult result = publisher.publish(request);

        assertEquals("editor-commit-2", result.commitSha);
        assertEquals(List.of("tree-before-runner", "tree-after-runner"), api.baseTrees);
        assertEquals(List.of("main-before-runner", "runner-commit"), api.parents);
        assertEquals(2, api.patchCount);
        assertTrue(api.forceValues.stream().noneMatch(Boolean::booleanValue));
        assertFalse(api.usedContentsPreflight);
    }

    private static final class FakeGitHub {
        private String head = "main-before-runner";
        private final Map<String, String> trees = new HashMap<>(Map.of(
                "main-before-runner", "tree-before-runner",
                "runner-commit", "tree-after-runner"));
        private final List<String> baseTrees = new ArrayList<>();
        private final List<String> parents = new ArrayList<>();
        private final List<Boolean> forceValues = new ArrayList<>();
        private int treeCount;
        private int commitCount;
        private int patchCount;
        private boolean usedContentsPreflight;

        String execute(String method, String path, JSONObject body) throws IOException {
            if (path.startsWith("contents/")) {
                usedContentsPreflight = true;
                throw new IOException("The publisher must not compare stale file SHAs.");
            }
            if ("GET".equals(method) && "git/ref/heads/main".equals(path)) {
                return new JSONObject().put("object", new JSONObject().put("sha", head)).toString();
            }
            if ("GET".equals(method) && path.startsWith("git/commits/")) {
                String commit = path.substring("git/commits/".length());
                return new JSONObject().put("tree", new JSONObject().put("sha", trees.get(commit))).toString();
            }
            if ("POST".equals(method) && "git/trees".equals(path)) {
                baseTrees.add(body.getString("base_tree"));
                String tree = "editor-tree-" + (++treeCount);
                return new JSONObject().put("sha", tree).toString();
            }
            if ("POST".equals(method) && "git/commits".equals(path)) {
                parents.add(body.getJSONArray("parents").getString(0));
                String commit = "editor-commit-" + (++commitCount);
                trees.put(commit, body.getString("tree"));
                return new JSONObject().put("sha", commit).toString();
            }
            if ("PATCH".equals(method) && "git/refs/heads/main".equals(path)) {
                patchCount++;
                forceValues.add(body.getBoolean("force"));
                if (patchCount == 1) {
                    // Simulate a runner commit landing between our read and ref update.
                    head = "runner-commit";
                    throw new IOException("HTTP 422: Update is not a fast forward");
                }
                head = body.getString("sha");
                return new JSONObject().put("object", new JSONObject().put("sha", head)).toString();
            }
            throw new IOException("Unexpected fake GitHub request: " + method + " " + path);
        }
    }
}
