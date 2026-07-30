package ru.scraper.coincatalog.scraper;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Запуск Chromium для Playwright.
 *
 * <p>Если задано свойство {@code browser} (CLI {@code --browser=/path}), используется
 * {@code executablePath}. Иначе — системные каналы chrome/msedge/chromium, затем bundled.
 */
@Slf4j
@Component
public class PlaywrightBrowserLauncher {

    private static final List<String> BROWSER_CHANNELS = List.of("chrome", "msedge", "chromium");

    private final String browserExecutablePath;

    /** Для тестов и ручного создания без Spring: путь не задан. */
    public PlaywrightBrowserLauncher() {
        this("");
    }

    @Autowired
    public PlaywrightBrowserLauncher(@Value("${browser:}") String browserExecutablePath) {
        this.browserExecutablePath = normalize(browserExecutablePath);
    }

    /** Путь из {@code --browser}, или {@code null} если не задан. */
    public String executablePath() {
        return browserExecutablePath;
    }

    public Browser launch(Playwright playwright, boolean headless, List<String> args) {
        return launch(playwright, headless, args, List.of());
    }

    public Browser launch(
            Playwright playwright,
            boolean headless,
            List<String> args,
            List<String> ignoreDefaultArgs) {
        if (browserExecutablePath != null) {
            BrowserType.LaunchOptions options = baseOptions(headless, args, ignoreDefaultArgs)
                    .setExecutablePath(Path.of(browserExecutablePath));
            Browser browser = playwright.chromium().launch(options);
            log.info("Браузер: executablePath={}", browserExecutablePath);
            return browser;
        }

        List<String> errors = new ArrayList<>();
        for (String channel : BROWSER_CHANNELS) {
            try {
                Browser browser = playwright.chromium()
                        .launch(baseOptions(headless, args, ignoreDefaultArgs).setChannel(channel));
                log.info("Браузер: {}", channel);
                return browser;
            } catch (PlaywrightException e) {
                errors.add(channel + ": " + e.getMessage());
            }
        }
        try {
            Browser browser = playwright.chromium()
                    .launch(baseOptions(headless, args, ignoreDefaultArgs));
            log.info("Браузер: playwright bundled chromium");
            return browser;
        } catch (PlaywrightException e) {
            errors.add("bundled: " + e.getMessage());
        }
        throw new IllegalStateException(
                "Не найден браузер для Playwright. Установите Google Chrome или Microsoft Edge,"
                        + " либо задайте --browser=/path/to/chromium.\n"
                        + String.join("\n", errors));
    }

    private static BrowserType.LaunchOptions baseOptions(
            boolean headless, List<String> args, List<String> ignoreDefaultArgs) {
        BrowserType.LaunchOptions options =
                new BrowserType.LaunchOptions().setHeadless(headless).setArgs(args);
        if (ignoreDefaultArgs != null && !ignoreDefaultArgs.isEmpty()) {
            options.setIgnoreDefaultArgs(ignoreDefaultArgs);
        }
        return options;
    }

    static String normalize(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        return path.strip();
    }
}
