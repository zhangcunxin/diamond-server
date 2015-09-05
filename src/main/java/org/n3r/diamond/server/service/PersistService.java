package org.n3r.diamond.server.service;

import com.alibaba.druid.pool.DruidDataSource;
import org.n3r.diamond.server.domain.DiamondStone;
import org.n3r.diamond.server.domain.Page;
import org.n3r.diamond.server.domain.PageHelper;
import org.n3r.diamond.server.utils.Encrypt;
import org.n3r.diamond.server.utils.Props;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.simple.ParameterizedRowMapper;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Properties;

import static org.apache.commons.lang3.StringUtils.isBlank;

@Service
public class PersistService {
    private static final int MAX_ROWS = 10000;
    private static final int QUERY_TIMEOUT = 2;

    private static final DiamondStoneRowMapper STONE_ROW_MAPPER = new DiamondStoneRowMapper();
    private String driverClassName;
    private String tableName;

    private static final class DiamondStoneRowMapper implements ParameterizedRowMapper<DiamondStone> {
        public DiamondStone mapRow(ResultSet rs, int rowNum) throws SQLException {
            DiamondStone info = new DiamondStone();
            info.setDataId(rs.getString("data_id"));
            info.setGroup(rs.getString("group_id"));
            info.setContent(rs.getString("content"));
            info.createIdAndMd5();
            info.setDescription(rs.getString("description"));
            info.setValid(rs.getBoolean("valid"));
            return info;
        }
    }

    private static String getPropStr(Properties props, String name) {
        return getPropStr(props, name, null);
    }

    private static String getPropStr(Properties props, String name, String defaultValue) {
        String srcValue = props.getProperty(name, defaultValue);
        if (srcValue != null) return Encrypt.tryDecrypt(srcValue, name);

        throw new IllegalArgumentException("property " + name + " is illegal");
    }

    private JdbcTemplate jt;

    @PostConstruct
    public void initDataSource() throws Exception {
        Properties props = readJdbcProperties();
        DataSource ds = createDataSource(props);
        createJdbcTemplate(ds);
    }

    private void createJdbcTemplate(DataSource ds) {
        jt = new JdbcTemplate();
        jt.setDataSource(ds);
        jt.setMaxRows(MAX_ROWS);
        jt.setQueryTimeout(QUERY_TIMEOUT);
    }

    private DataSource createDataSource(Properties props) {
        DruidDataSource ds = new DruidDataSource();

        driverClassName = getPropStr(props, ("db.driver"));
        tableName = getPropStr(props, "db.tableName", "diamond_stones");
        ds.setDriverClassName(driverClassName);
        ds.setUrl(getPropStr(props, "db.url"));
        ds.setUsername(getPropStr(props, "db.user"));
        ds.setPassword(getPropStr(props, "db.password"));
        ds.setInitialSize(Integer.parseInt(getPropStr(props, "db.initialSize")));
        ds.setMaxActive(Integer.parseInt(getPropStr(props, "db.maxActive")));
        ds.setMaxWait(Long.parseLong(getPropStr(props, "db.maxWait")));
        ds.setPoolPreparedStatements(Boolean.parseBoolean(getPropStr(props, "db.poolPreparedStatements")));
        ds.setMaxWait(10 * 1000);

        return ds;
    }

    private Properties readJdbcProperties() throws IOException {
        return Props.tryProperties("diamond-jdbc.properties", ".diamond-server");
    }

    public void addConfigInfo(final DiamondStone diamondStone) {
        String sql = "insert into " + tableName
                + "(id,data_id,group_id,content,gmt_create,gmt_modified,description,valid) "
                + " values(?,?,?,?,?,?,?,?)";
        PreparedStatementSetter pss = new PreparedStatementSetter() {
            public void setValues(PreparedStatement ps) throws SQLException {
                int index = 1;
                ps.setString(index++, diamondStone.getId());
                ps.setString(index++, diamondStone.getDataId());
                ps.setString(index++, diamondStone.getGroup());
                ps.setString(index++, diamondStone.getContent());
                Timestamp time = new Timestamp(System.currentTimeMillis());
                ps.setTimestamp(index++, time);
                ps.setTimestamp(index++, time);
                ps.setString(index++, diamondStone.getDescription());
                ps.setBoolean(index++, diamondStone.isValid());
            }
        };
        jt.update(sql, pss);
    }


    public void removeConfigInfo(final DiamondStone diamondStone) {
        String sql = "delete from " + tableName + " where id=?";
        jt.update(sql, new PreparedStatementSetter() {
            public void setValues(PreparedStatement ps) throws SQLException {
                ps.setString(1, diamondStone.getId());
            }
        });
    }

    public void updateConfigInfo(final DiamondStone diamondStone) {
        String sql = "update " + tableName +
                " set content=?,gmt_modified=?,description=?,valid=? " +
                " where id=?";
        PreparedStatementSetter pss = new PreparedStatementSetter() {
            public void setValues(PreparedStatement ps) throws SQLException {
                int index = 1;
                ps.setString(index++, diamondStone.getContent());
                ps.setTimestamp(index++, new Timestamp(System.currentTimeMillis()));
                ps.setString(index++, diamondStone.getDescription());
                ps.setBoolean(index++, diamondStone.isValid());
                ps.setString(index++, diamondStone.getId());
            }
        };
        jt.update(sql, pss);
    }


    public DiamondStone findConfigInfo(final String dataId, final String group) {
        try {
            String sql = "select id,data_id,group_id,content,description,valid " +
                    " from " + tableName + " where data_id=? and group_id=? order by group_id, data_id";
            return jt.queryForObject(sql, new Object[]{dataId, group}, STONE_ROW_MAPPER);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public DiamondStone findConfigInfo(String id) {
        try {
            String sql = "select id,data_id,group_id,content,description,valid " +
                    " from " + tableName + " where id=? order by group_id, data_id";
            return jt.queryForObject(sql, new Object[]{id}, STONE_ROW_MAPPER);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public Page<DiamondStone> findConfigInfoByDataId(int pageNo, int pageSize, String dataId) {
        String sqlCountRows = "select count(id) from " + tableName + " where data_id=?";
        String sqlFetchRows = "select id,data_id,group_id,content,description,valid " +
                " from " + tableName + " where data_id=? order by group_id, data_id";
        return PageHelper.fetchPage(driverClassName, jt, sqlCountRows,
                sqlFetchRows, new Object[]{dataId}, pageNo, pageSize, STONE_ROW_MAPPER);
    }

    public Page<DiamondStone> findConfigInfoByGroup(int pageNo, int pageSize, String group) {
        String sqlCountRows = "select count(id) from " + tableName + " where group_id=?";
        String sqlFetchRows = "select id,data_id,group_id,content,description,valid " +
                " from " + tableName + " where group_id=? order by group_id, data_id";
        return PageHelper.fetchPage(driverClassName, jt, sqlCountRows,
                sqlFetchRows, new Object[]{group}, pageNo, pageSize, STONE_ROW_MAPPER);
    }

    public Page<DiamondStone> findAllConfigInfo(int pageNo, int pageSize) {
        String sqlCountRows = "select count(id) from " + tableName;
        String sqlFetchRows = "select id,data_id,group_id,content,description,valid " +
                " from " + tableName + " order by group_id, data_id";
        return PageHelper.fetchPage(driverClassName, jt, sqlCountRows,
                sqlFetchRows, new Object[]{}, pageNo, pageSize, STONE_ROW_MAPPER);
    }

    public Page<DiamondStone> findConfigInfoLike(int pageNo, int pageSize, String dataId, String group) {
        if (isBlank(dataId) && isBlank(group))
            return findAllConfigInfo(pageNo, pageSize);

        String sqlCountRows = "select count(id) from " + tableName + " where ";
        String sqlFetchRows = "select id,data_id,group_id,content,description,valid " +
                " from " + tableName + " where ";

        if (!isBlank(dataId)) {
            sqlCountRows += "data_id like ? ";
            sqlFetchRows += "data_id like ? ";
        }

        if (!isBlank(group)) {
            if (!isBlank(dataId)) {
                sqlCountRows += "and ";
                sqlFetchRows += "and ";
            }

            sqlCountRows += "group_id like ? ";
            sqlFetchRows += "group_id like ? ";
        }

        sqlFetchRows += " order by group_id, data_id";

        Object[] args = null;
        if (!isBlank(dataId) && !isBlank(group)) {
            args = new Object[]{createLikeArg(dataId), createLikeArg(group)};
        } else if (!isBlank(dataId)) {
            args = new Object[]{createLikeArg(dataId)};
        } else if (!isBlank(group)) {
            args = new Object[]{createLikeArg(group)};
        }

        return PageHelper.fetchPage(driverClassName, jt, sqlCountRows,
                sqlFetchRows, args, pageNo, pageSize, STONE_ROW_MAPPER);
    }

    private String createLikeArg(String s) {
        if (s.indexOf("*") >= 0)
            return s.replaceAll("\\*", "%");

        return "%" + s + "%";
    }
}
