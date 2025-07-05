package com.ndb.auction.dao.oracle.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import com.ndb.auction.dao.oracle.BaseOracleDao;
import com.ndb.auction.dao.oracle.Table;
import com.ndb.auction.models.auth.GAuthResetRequest;

import org.springframework.stereotype.Repository;

import lombok.NoArgsConstructor;

@Repository
@NoArgsConstructor
@Table(name = "TBL_GAUTH_RESET")
public class GAuthResetRequestDao extends BaseOracleDao {

    private static GAuthResetRequest extract(ResultSet rs) throws SQLException {
        GAuthResetRequest m = GAuthResetRequest.builder()
                .userId(rs.getInt("USER_ID"))
                .secret(rs.getString("SECRET"))
                .token(rs.getString("TOKEN"))
                .requestedAt(rs.getTimestamp("REQUESTED_AT").getTime())
                .updatedAt(rs.getTimestamp("UPDATED_AT").getTime())
                .status(rs.getInt("STATUS"))
                .build();
        return m;
    }

    public int save(GAuthResetRequest m) {
        // First try to clean up any existing pending requests for this user
        try {
            String cleanupSql = "UPDATE TBL_GAUTH_RESET SET STATUS = 2, UPDATED_AT = SYSDATE WHERE USER_ID = ? AND STATUS = 0";
            jdbcTemplate.update(cleanupSql, m.getUserId());
        } catch (Exception e) {
            // Log but continue - cleanup is not critical
            System.err.println("Warning during cleanup: " + e.getMessage());
        }

        // Use MERGE to handle duplicate entries gracefully
        var sql = "MERGE INTO TBL_GAUTH_RESET USING DUAL ON (USER_ID = ? AND TOKEN = ?) " +
                "WHEN MATCHED THEN UPDATE SET SECRET = ?, UPDATED_AT = SYSDATE, STATUS = 0 " +
                "WHEN NOT MATCHED THEN INSERT (USER_ID, SECRET, TOKEN, REQUESTED_AT, UPDATED_AT, STATUS) " +
                "VALUES (?, ?, ?, SYSDATE, SYSDATE, 0)";

        return jdbcTemplate.update(sql,
                m.getUserId(), m.getToken(), // for the ON condition
                m.getSecret(), // for UPDATE
                m.getUserId(), m.getSecret(), m.getToken() // for INSERT
        );
    }

    public GAuthResetRequest selectById(int userId, String token) {
        var sql = "SELECT * FROM TBL_GAUTH_RESET WHERE USER_ID = ? AND TOKEN = ? AND STATUS = 0";
        return jdbcTemplate.query(sql, rs -> {
            if (!rs.next())
                return null;
            return extract(rs);
        }, userId, token);
    }

    public List<GAuthResetRequest> selectByUser(int userId) {
        var sql = "SELECT * FROM TBL_GAUTH_RESET WHERE USER_ID = ?";
        return jdbcTemplate.query(sql, (rs, rownumber) -> extract(rs), userId);
    }

    public int updateRequest(int userId, String token, int status) {
        var sql = "UPDATE TBL_GAUTH_RESET SET STATUS = ?, UPDATED_AT = SYSDATE WHERE USER_ID = ? AND TOKEN = ?";
        return jdbcTemplate.update(sql, status, userId, token);
    }
}
