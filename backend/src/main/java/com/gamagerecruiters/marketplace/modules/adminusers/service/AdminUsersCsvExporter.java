package com.gamagerecruiters.marketplace.modules.adminusers.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.modules.adminusers.domain.AdminUsersRules;
import com.gamagerecruiters.marketplace.modules.adminusers.port.UserDirectoryPort.UserAccount;

/**
 * CSV export of the admin users list: same filters as the list, newest joined first, at most
 * {@link AdminUsersRules#EXPORT_MAX_ROWS} rows. Columns: id, name, email, role, joined (UTC ISO-8601), status.
 *
 * <p>CSV injection: a value starting with = + - @ tab or carriage return gets a leading apostrophe, so a spreadsheet
 * shows it as text instead of running it as a formula. Values with a comma, quote or line break are quoted (RFC 4180).
 * The file starts with a UTF-8 byte order mark so spreadsheet apps read non-English names correctly.</p>
 */
@Service
public class AdminUsersCsvExporter {

    public record Export(byte[] content, String filename, int rows, long totalMatching, boolean truncated) {
    }

    static final String HEADER = "id,name,email,role,joined,status";
    private static final String BOM = "﻿";
    private static final String FORMULA_START = "=+-@\t\r";

    private static final Logger log = LoggerFactory.getLogger(AdminUsersCsvExporter.class);

    private final AdminUsersQueryService queryService;
    private final Clock clock;

    @Autowired
    public AdminUsersCsvExporter(AdminUsersQueryService queryService) {
        this(queryService, Clock.systemUTC());
    }

    AdminUsersCsvExporter(AdminUsersQueryService queryService, Clock clock) {
        this.queryService = queryService;
        this.clock = clock;
    }

    public Export export(String actorId, String q, String role, String status) {
        AdminUsersQueryService.Filters filters = queryService.filters(q, role, status);
        List<UserAccount> rows = new ArrayList<>();
        long total = 0;
        int page = 0;
        while (rows.size() < AdminUsersRules.EXPORT_MAX_ROWS) {
            Page<UserAccount> result = queryService.find(actorId, filters,
                    PageRequest.of(page, AdminUsersRules.EXPORT_PAGE_SIZE, AdminUsersQueryService.newestFirst()));
            total = result.getTotalElements();
            for (UserAccount account : result.getContent()) {
                if (rows.size() == AdminUsersRules.EXPORT_MAX_ROWS) {
                    break;
                }
                rows.add(account);
            }
            if (!result.hasNext() || result.getContent().isEmpty()) {
                break;
            }
            page++;
        }

        StringBuilder csv = new StringBuilder(BOM).append(HEADER).append("\r\n");
        for (UserAccount u : rows) {
            csv.append(cell(u.id())).append(',')
                    .append(cell(u.displayName())).append(',')
                    .append(cell(u.email())).append(',')
                    .append(cell(u.role() == null ? null : u.role().name())).append(',')
                    .append(cell(u.joinedAt() == null ? null : u.joinedAt().toString())).append(',')
                    .append(cell(u.status() == null ? null : u.status().name()))
                    .append("\r\n");
        }
        boolean truncated = total > rows.size();
        String filename = "users-" + LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_LOCAL_DATE) + ".csv";
        log.info("event=ADMIN_USERS_EXPORTED actorId={} rows={} truncated={}", actorId, rows.size(), truncated);
        return new Export(csv.toString().getBytes(StandardCharsets.UTF_8), filename, rows.size(), total, truncated);
    }

    /** One CSV cell, safe against formula injection. */
    static String cell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String text = FORMULA_START.indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }
}
