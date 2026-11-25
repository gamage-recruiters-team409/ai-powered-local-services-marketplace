package com.gamagerecruiters.marketplace.modules.adminusers.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.gamagerecruiters.marketplace.modules.adminusers.port.AccountActionAuditPort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderListingPort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderProfilePort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderReviewStatsPort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.UserBookingStatsPort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.UserDirectoryPort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.UserDirectoryPort.UserAccount;
import com.gamagerecruiters.marketplace.modules.adminusers.port.UserDisputeStatsPort;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;

class AdminUsersCsvExporterTest {

    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

    private UserDirectoryPort directory;
    private AdminUsersCsvExporter exporter;

    @BeforeEach
    void setUp() {
        directory = mock(UserDirectoryPort.class);
        AdminUsersQueryService query = new AdminUsersQueryService(directory, mock(UserBookingStatsPort.class),
                mock(ProviderReviewStatsPort.class), mock(ProviderProfilePort.class), mock(ProviderListingPort.class),
                mock(UserDisputeStatsPort.class), mock(AccountActionAuditPort.class), Clock.fixed(NOW, ZoneOffset.UTC));
        exporter = new AdminUsersCsvExporter(query, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static UserAccount user(String id, String name, String email) {
        return new UserAccount(id, name, email, "+94770000000", Role.CUSTOMER, UserStatus.ACTIVE, true,
                Instant.parse("2026-03-28T03:30:00Z"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "=1+1|'=1+1",
            "+SUM(A1)|'+SUM(A1)",
            "-2+3|'-2+3",
            "@cmd|'@cmd",
            "plain name|plain name",
            "`a,b`|`\"a,b\"`",
            "`say \"hi\"`|`\"say \"\"hi\"\"\"`",
            "`=HYPERLINK(\"x\",\"y\")`|`\"'=HYPERLINK(\"\"x\"\",\"\"y\"\")\"`",
            "a=b|a=b"
    })
    @DisplayName("CSV injection: = + - @ start gets an apostrophe; commas and quotes are quoted (RFC 4180)")
    void cellEscaping(String value, String expected) {
        assertThat(AdminUsersCsvExporter.cell(value)).isEqualTo(expected);
    }

    @Test
    @DisplayName("CSV injection: a value starting with a tab or carriage return gets an apostrophe too; null is empty")
    void cellControlCharacters() {
        assertThat(AdminUsersCsvExporter.cell("\tcmd")).isEqualTo("'\tcmd");
        assertThat(AdminUsersCsvExporter.cell("\rcmd")).isEqualTo("\"'\rcmd\"");
        assertThat(AdminUsersCsvExporter.cell("line1\nline2")).isEqualTo("\"line1\nline2\"");
        assertThat(AdminUsersCsvExporter.cell(null)).isEmpty();
    }

    @Test
    @DisplayName("export: header, escaped rows, UTF-8 BOM, dated file name, filters passed to the directory")
    void export() {
        when(directory.search(eq("admin-1"), any(), eq(UserStatus.ACTIVE), eq("perera"), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(List.of(user("u1", "=cmd|' /C calc'!A0", "a@example.test"),
                        user("u2", "Perera, Nimal", "b@example.test")), inv.getArgument(4), 2));

        AdminUsersCsvExporter.Export export = exporter.export("admin-1", "perera", "CUSTOMER", "ACTIVE");

        String csv = new String(export.content(), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿id,name,email,role,joined,status\r\n");
        assertThat(csv).contains("u1,'=cmd|' /C calc'!A0,a@example.test,CUSTOMER,2026-03-28T03:30:00Z,ACTIVE\r\n");
        assertThat(csv).contains("u2,\"Perera, Nimal\",b@example.test,CUSTOMER,");
        assertThat(csv).doesNotContain("+94770000000");
        assertThat(export.filename()).isEqualTo("users-2026-11-19.csv");
        assertThat(export.rows()).isEqualTo(2);
        assertThat(export.truncated()).isFalse();
    }

    @Test
    @DisplayName("export: at most 5000 rows even when more match (50 pages of 100), marked truncated")
    void rowLimit() {
        when(directory.search(anyString(), any(), any(), any(), any(Pageable.class))).thenAnswer(inv -> {
            Pageable pageable = inv.getArgument(4);
            List<UserAccount> content = new ArrayList<>();
            IntStream.range(0, pageable.getPageSize()).forEach(i -> content.add(
                    user("u" + (pageable.getPageNumber() * 100 + i), "User", "u@example.test")));
            return new PageImpl<>(content, pageable, 7500);
        });

        AdminUsersCsvExporter.Export export = exporter.export("admin-1", null, null, null);

        assertThat(export.rows()).isEqualTo(5000);
        assertThat(export.totalMatching()).isEqualTo(7500);
        assertThat(export.truncated()).isTrue();
        String csv = new String(export.content(), StandardCharsets.UTF_8);
        assertThat(csv.split("\r\n")).hasSize(5001);
        verify(directory, times(50)).search(anyString(), any(), any(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("export by user id: exact lookup, a single row, no text search")
    void exportById() {
        String id = "65f1c0aa11bb22cc33a1b2c3";
        when(directory.findById(id)).thenReturn(Optional.of(user(id, "Nimal", "n@example.test")));

        AdminUsersCsvExporter.Export export = exporter.export("admin-1", id, null, null);

        assertThat(export.rows()).isEqualTo(1);
        verify(directory, never()).search(any(), any(), any(), any(), any());
    }
}
