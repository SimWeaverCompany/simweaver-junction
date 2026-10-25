package com.simweaver.common.iem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Checked exception thrown when FOM modules cannot be loaded, parsed or merged into an
 * {@link InformationExchangeModel}.
 * <p>
 * Merging collects every inconsistency it finds before giving up, so one exception may carry
 * several problems. {@link #getMessage()} contains a summary (the first problems, one per line);
 * {@link #getProblems()} returns all of them. Each problem is prefixed with its location when known,
 * e.g. {@code "NETN-BASE.xml:123: ..."}.
 */
public class IemException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Maximum number of problems repeated in {@link #getMessage()}. */
    private static final int MAX_IN_MESSAGE = 25;

    private final ArrayList<String> problems;

    public IemException(String message) {
        super(message);
        this.problems = new ArrayList<String>(Collections.singletonList(message));
    }

    public IemException(String message, Throwable cause) {
        super(message, cause);
        this.problems = new ArrayList<String>(Collections.singletonList(message));
    }

    /**
     * @param headline short description, e.g. "FOM merge failed"
     * @param problems every problem found; must not be empty
     */
    public IemException(String headline, List<String> problems) {
        super(buildMessage(headline, problems));
        this.problems = new ArrayList<String>(problems);
    }

    /** All problems found, in the order they were detected. Never empty. */
    public List<String> getProblems() {
        return Collections.unmodifiableList(problems);
    }

    private static String buildMessage(String headline, List<String> problems) {
        StringBuilder sb = new StringBuilder(headline);
        sb.append(" (").append(problems.size()).append(problems.size() == 1 ? " problem)" : " problems)");
        int n = Math.min(problems.size(), MAX_IN_MESSAGE);
        for (int i = 0; i < n; i++) {
            sb.append("\n  - ").append(problems.get(i));
        }
        if (problems.size() > n) {
            sb.append("\n  ... and ").append(problems.size() - n).append(" more (see getProblems())");
        }
        return sb.toString();
    }
}
