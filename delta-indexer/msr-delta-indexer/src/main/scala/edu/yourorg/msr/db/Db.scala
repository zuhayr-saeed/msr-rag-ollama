package edu.yourorg.msr.db

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

object Db {
  lazy val ds: HikariDataSource = {
    val url  = sys.env.getOrElse("RAG_DB_URL",  "jdbc:postgresql://127.0.0.1:5432/rag")
    val user = sys.env.getOrElse("RAG_DB_USER", "rag")
    val pass = sys.env.getOrElse("RAG_DB_PASS", "rag")
    val cfg  = new HikariConfig()
    cfg.setJdbcUrl(url)
    cfg.setUsername(user)
    cfg.setPassword(pass)
    cfg.setMaximumPoolSize(4)
    new HikariDataSource(cfg)
  }
}
