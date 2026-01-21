-- A versioned, denormalized index ready for serving
create table if not exists rag.retrieval_index(
  version_id    text not null,
  chunk_id      text not null references rag.chunks(chunk_id) on delete cascade,
  doc_id        text not null,
  chunk_ix      int  not null,
  section_path  text,
  text          text not null,
  title         text,
  language      text,
  embedder      text not null,
  embedder_ver  text not null,
  vector        bytea not null,
  content_hash  text not null,
  created_at    timestamptz not null default now(),
  primary key (version_id, chunk_id, embedder, embedder_ver)
);

-- Fast filters
create index if not exists rag_ri_doc          on rag.retrieval_index(doc_id);
create index if not exists rag_ri_embed        on rag.retrieval_index(embedder, embedder_ver);
create index if not exists rag_ri_version_time on rag.retrieval_index(created_at);

-- Convenience view: "current" points to the latest version row in rag.index_versions
create or replace view rag.retrieval_index_current as
select ri.*
from rag.retrieval_index ri
join (
  select version_id
  from rag.index_versions
  order by created_at desc
  limit 1
) v on ri.version_id = v.version_id;
