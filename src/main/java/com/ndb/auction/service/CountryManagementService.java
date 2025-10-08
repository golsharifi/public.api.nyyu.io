package com.ndb.auction.service;

import com.ndb.auction.dao.oracle.BaseOracleDao;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@Slf4j
public class CountryManagementService extends BaseOracleDao {

    /**
     * Get all countries with verification exemption status
     */
    public List<Map<String, Object>> getAllCountriesWithVerificationStatus() {
        String sql = """
                    SELECT DISTINCT
                        u.COUNTRY as country_code,
                        COUNT(*) as user_count,
                        CASE
                            WHEN cs.COUNTRY_CODE IS NOT NULL THEN 1
                            ELSE 0
                        END as verification_exempt
                    FROM TBL_USER u
                    LEFT JOIN TBL_COUNTRY_SETTINGS cs ON u.COUNTRY = cs.COUNTRY_CODE
                    WHERE u.DELETED = 0
                    GROUP BY u.COUNTRY, cs.COUNTRY_CODE
                    ORDER BY u.COUNTRY
                """;

        return jdbcTemplate.queryForList(sql);
    }

    /**
     * Update country verification settings
     */
    public int updateCountryVerificationSettings(String countryCode, boolean verificationExempt, boolean blocked) {
        String sql = """
                    MERGE INTO TBL_COUNTRY_SETTINGS USING DUAL ON (COUNTRY_CODE = ?)
                    WHEN MATCHED THEN UPDATE SET
                        VERIFICATION_EXEMPT = ?,
                        BLOCKED = ?,
                        UPDATE_DATE = SYSDATE
                    WHEN NOT MATCHED THEN INSERT (
                        COUNTRY_CODE, VERIFICATION_EXEMPT, BLOCKED, CREATE_DATE, UPDATE_DATE
                    ) VALUES (?, ?, ?, SYSDATE, SYSDATE)
                """;

        return jdbcTemplate.update(sql,
                countryCode.toUpperCase(),
                verificationExempt ? 1 : 0,
                blocked ? 1 : 0,
                countryCode.toUpperCase(),
                verificationExempt ? 1 : 0,
                blocked ? 1 : 0);
    }

    /**
     * Get country settings
     */
    public Map<String, Object> getCountrySettings(String countryCode) {
        String sql = """
                    SELECT COUNTRY_CODE, VERIFICATION_EXEMPT, BLOCKED
                    FROM TBL_COUNTRY_SETTINGS
                    WHERE COUNTRY_CODE = ?
                """;

        List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, countryCode.toUpperCase());
        if (results.isEmpty()) {
            Map<String, Object> defaultSettings = new HashMap<>();
            defaultSettings.put("COUNTRY_CODE", countryCode.toUpperCase());
            defaultSettings.put("VERIFICATION_EXEMPT", 0);
            defaultSettings.put("BLOCKED", 0);
            return defaultSettings;
        }
        return results.get(0);
    }
}