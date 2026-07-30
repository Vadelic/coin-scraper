package ru.scraper.coincatalog.scraper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PlaywrightBrowserLauncherTest {

    @Test
    void blankPathMeansChannelMode() {
        assertThat(new PlaywrightBrowserLauncher().executablePath()).isNull();
        assertThat(new PlaywrightBrowserLauncher("").executablePath()).isNull();
        assertThat(new PlaywrightBrowserLauncher("   ").executablePath()).isNull();
    }

    @Test
    void nonBlankPathIsKeptStripped() {
        var launcher = new PlaywrightBrowserLauncher("  /opt/sberbrowser  ");
        assertThat(launcher.executablePath()).isEqualTo("/opt/sberbrowser");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void normalizeBlankReturnsNull(String input) {
        assertThat(PlaywrightBrowserLauncher.normalize(input)).isNull();
    }

    @Test
    void normalizeStripsWhitespace() {
        assertThat(PlaywrightBrowserLauncher.normalize(" C:\\Sber\\sberbrowser.exe "))
                .isEqualTo("C:\\Sber\\sberbrowser.exe");
    }
}
