create schema if not exists rag;

create table if not exists rag.documents(
  doc_id        text primary key,
  uri           text not null,
  title         text,
  language      text,
  content_hash  text not null,
  updated_at    timestamptz not null default now()
);

create table if not exists rag.chunks(
  chunk_id      text primary key,
  doc_id        text not null references rag.documents(doc_id) on delete cascade,
  chunk_ix      int  not null,
  start_pos     int  not null,
  end_pos       int  not null,
  section_path  text,
  text          text not null,
  content_hash  text not null,
  updated_at    timestamptz not null default now()
);

create table if not exists rag.embeddings(
  chunk_id      text not null references rag.chunks(chunk_id) on delete cascade,
  content_hash  text not null,
  embedder      text not null,
  embedder_ver  text not null,
  vector        bytea not null,
  updated_at    timestamptz not null default now(),
  primary key (chunk_id, embedder, embedder_ver)
);

create table if not exists rag.index_versions(
  version_id    text primary key,
  created_at    timestamptz not null default now(),
  embedder      text not null,
  embedder_ver  text not null,
  notes         text
);
