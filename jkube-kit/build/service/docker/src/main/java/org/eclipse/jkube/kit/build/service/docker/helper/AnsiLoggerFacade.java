/*
 * Copyright (c) 2019 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at:
 *
 *     https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.jkube.kit.build.service.docker.helper;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jkube.kit.common.KitLogger;
import org.apache.commons.lang3.StringUtils;
import org.fusesource.jansi.Ansi;
import org.fusesource.jansi.AnsiConsole;

import static org.fusesource.jansi.Ansi.Color.BLACK;
import static org.fusesource.jansi.Ansi.Color.BLUE;
import static org.fusesource.jansi.Ansi.Color.CYAN;
import static org.fusesource.jansi.Ansi.Color.DEFAULT;
import static org.fusesource.jansi.Ansi.Color.GREEN;
import static org.fusesource.jansi.Ansi.Color.MAGENTA;
import static org.fusesource.jansi.Ansi.Color.RED;
import static org.fusesource.jansi.Ansi.Color.WHITE;
import static org.fusesource.jansi.Ansi.Color.YELLOW;
import static org.fusesource.jansi.Ansi.ansi;

/**
 * Simple log handler for printing used during the maven build
 *
 * @author roland
 * @since 31.03.14
 */
public class AnsiLoggerFacade implements KitLogger {

    // prefix used for console output
    public static final String DEFAULT_LOG_PREFIX = "DOCKER> ";
    private static final int NON_ANSI_UPDATE_PERIOD = 80;

    private final KitLogger log;
    private final String prefix;
    private final boolean batchMode;
    private boolean verbose;

    // ANSI escapes for various colors (or empty strings if no coloring is used)
    static Ansi.Color
            COLOR_ERROR = RED,
            COLOR_INFO = GREEN,
            COLOR_WARNING = YELLOW,
            COLOR_PROGRESS_ID = YELLOW,
            COLOR_PROGRESS_STATUS = GREEN,
            COLOR_PROGRESS_BAR = CYAN,
            COLOR_EMPHASIS = BLUE;


    // Map remembering lines
    private ThreadLocal<Map<String, Integer>> imageLines = new ThreadLocal<>();
    private ThreadLocal<AtomicInteger> updateCount = new ThreadLocal<>();

    // Whether to use ANSI codes
    private boolean useAnsi;

    public AnsiLoggerFacade(KitLogger log, boolean useColor, boolean verbose) {
        this(log, useColor, verbose, false);
    }

    public AnsiLoggerFacade(KitLogger log, boolean useColor, boolean verbose, boolean batchMode) {
        this(log, useColor, verbose, batchMode, DEFAULT_LOG_PREFIX);
    }

    public AnsiLoggerFacade(KitLogger log, boolean useColor, boolean verbose, boolean batchMode, String prefix) {
        this.log = log;
        this.prefix = prefix;
        this.verbose = verbose;
        this.batchMode = batchMode;
        initializeColor(useColor);
    }

    /** {@inheritDoc} */
    public void debug(String message, Object... params) {
        if (isDebugEnabled()) {
            log.debug(prefix + format(message, params));
        }
    }

    /** {@inheritDoc} */
    public void info(String message, Object... params) {
        log.info(colored(message, COLOR_INFO, true, params));
    }

    /** {@inheritDoc} */
    public void verbose(String message, Object ... params) {
        if (verbose) {
            log.info(ansi().fgBright(BLACK).a(prefix).a(format(message, params)).reset().toString());
        }
    }

    /** {@inheritDoc} */
    public void warn(String format, Object... params) {
        log.warn(colored(format, COLOR_WARNING, true, params));
    }

    /** {@inheritDoc} */
    public void error(String message, Object... params) {
        log.error(colored(message, COLOR_ERROR, true, params));
    }

    /**
     * Whether debugging is enabled.
     */
    public boolean isDebugEnabled() {
        return log.isDebugEnabled();
    }

    public boolean isVerboseEnabled() {
        return verbose;
    }

    /**
     * Start a progress bar
     */
    public void progressStart() {
        // A progress indicator is always written out to standard out if a tty is enabled.
        if (!batchMode) {
            imageLines.remove();
            updateCount.remove();
            imageLines.set(new HashMap<>());
            updateCount.set(new AtomicInteger());
        }
    }

    /**
     * Update the progress
     */
    public void progressUpdate(String layerId, String status, String progressMessage) {
        if (!batchMode && StringUtils.isNotEmpty(layerId)) {
            if (useAnsi) {
                updateAnsiProgress(layerId, status, progressMessage);
            } else {
                updateNonAnsiProgress(layerId);
            }
            flush();
        }
    }

    private void updateAnsiProgress(String imageId, String status, String progressMessage) {
        Map<String,Integer> imgLineMap = imageLines.get();
        Integer line = imgLineMap.get(imageId);

        int diff = 0;
        if (line == null) {
            line = imgLineMap.size();
            imgLineMap.put(imageId, line);
        } else {
            diff = imgLineMap.size() - line;
        }

        if (diff > 0) {
            print(ansi().cursorUp(diff).eraseLine(Ansi.Erase.ALL).toString());
        }

        // Status with progress bars: (max length = 11, hence pad to 11)
        // Extracting
        // Downloading
        String progress = progressMessage != null ? progressMessage : "";
        String msg =
            ansi()
                .fg(COLOR_PROGRESS_ID).a(imageId).reset().a(": ")
                .fg(COLOR_PROGRESS_STATUS).a(StringUtils.rightPad(status,11) + " ")
                .fg(COLOR_PROGRESS_BAR).a(progress).toString();
        println(msg);

        if (diff > 0) {
            // move cursor back down to bottom
            print(ansi().cursorDown(diff - 1).toString());
        }
    }

    private void updateNonAnsiProgress(String imageId) {
        AtomicInteger count = updateCount.get();
        int nr = count.getAndIncrement();
        if (nr % NON_ANSI_UPDATE_PERIOD == 0) {
            print("#");
        }
        if (nr > 0 && nr % (80 * NON_ANSI_UPDATE_PERIOD) == 0) {
            print("\n");
        }
    }

    /**
     * Finis progress meter. Must be always called if {@link #progressStart()} has been used.
     */
    public void progressFinished() {
        if (!batchMode) {
            imageLines.remove();
            print(ansi().reset().toString());
            if (!useAnsi) {
                println("");
            }
        }
    }

    private void flush() {
        System.out.flush();
    }

    private void initializeColor(boolean useColor) {
        // sl4j simple logger used by Maven seems to escape ANSI escapes on Windows
        this.useAnsi = useColor && System.console() != null && !log.isDebugEnabled() && !isWindows();
        if (useAnsi) {
            AnsiConsole.systemInstall();
            Ansi.setEnabled(true);
        }
        else {
            Ansi.setEnabled(false);
        }
    }

    private boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase().startsWith("windows");
    }

    private void println(String txt) {
        System.out.println(txt);
    }

    private void print(String txt) {
        System.out.print(txt);
    }

    private String colored(String message, Ansi.Color color, boolean addPrefix, Object... params) {
        Ansi ansi = ansi().fg(color);
        String msgToPrint = addPrefix ? prefix + message : message;
        return ansi.a(format(evaluateEmphasis(msgToPrint, color), params)).reset().toString();
    }

    // Use parameters when given, otherwise we use the string directly
    private String format(String message, Object[] params) {
        if (params.length == 0) {
            return message;
        } else if (params.length == 1 && params[0] instanceof Throwable) {
            // We print only the message here since breaking exception will bubble up
            // anyway
            return message + ": " + params[0].toString();
        } else {
            return String.format(message, params);
        }
    }

    // Emphasize parts encloses in "[[*]]" tags
    private String evaluateEmphasis(String message, Ansi.Color msgColor) {
        // Split with delimiters [[.]]. See also http://stackoverflow.com/a/2206545/207604
        String prepared = message.replaceAll("\\[\\[(.)]]", "[[]]$1[[]]");
        String[] parts = prepared.split("\\[\\[]]");
        if (parts.length == 1) {
            return message;
        }
        String msgColorS = ansi().fg(msgColor).toString();
        StringBuilder ret = new StringBuilder(parts[0]);
        boolean colorOpen = true;
        for (int i = 1; i < parts.length; i+=2) {
            ret.append(colorOpen ? getEmphasisColor(parts[i]) : msgColorS);
            colorOpen = !colorOpen;
            if (i+1 < parts.length) {
                ret.append(parts[i+1]);
            }
        }
        return ret.toString();
    }

    private static final Map<String, Ansi.Color> COLOR_MAP = new HashMap<>();

    static {
        COLOR_MAP.put("*", COLOR_EMPHASIS);
        COLOR_MAP.put("B", BLUE);
        COLOR_MAP.put("C", CYAN);
        COLOR_MAP.put("Y", YELLOW);
        COLOR_MAP.put("G", GREEN);
        COLOR_MAP.put("M", MAGENTA);
        COLOR_MAP.put("R", RED);
        COLOR_MAP.put("W", WHITE);
        COLOR_MAP.put("S", BLACK);
        COLOR_MAP.put("D", DEFAULT);
    }

    private String getEmphasisColor(String id) {
        Ansi.Color color = COLOR_MAP.get(id.toUpperCase());
        if (color != null) {
            return id.toLowerCase().equals(id) ?
                // lower case letter means bright color ...
                ansi().fgBright(color).toString() :
                ansi().fg(color).toString();
        } else {
            return "";
        }
    }
}
