(ns pokemon.migration.backup
  "Backup/restauração de storage Datomic Local (diretório de arquivos)."
  (:require [clojure.java.io :as io]
            [odisseu.persistence.datomic :as persistence])
  (:import [java.nio.file Files FileVisitResult SimpleFileVisitor]
           [java.nio.file.attribute BasicFileAttributes]
           [java.nio.file Path Paths StandardCopyOption]))

(defn- path
  ^Path [s]
  (Paths/get (str s) (into-array String [])))

(defn- copy-tree!
  [^Path from ^Path to]
  (Files/walkFileTree
   from
   (proxy [SimpleFileVisitor] []
     (preVisitDirectory [^Path dir ^BasicFileAttributes _attrs]
       (let [rel (.relativize from dir)
             target (.resolve to (str rel))]
         (when-not (Files/exists target (make-array java.nio.file.LinkOption 0))
           (Files/createDirectories target (make-array java.nio.file.attribute.FileAttribute 0))))
       FileVisitResult/CONTINUE)
     (visitFile [^Path file ^BasicFileAttributes _attrs]
       (let [rel (.relativize from file)
             target (.resolve to (str rel))]
         (Files/createDirectories (.getParent target)
                                  (make-array java.nio.file.attribute.FileAttribute 0))
         (Files/copy file target
                     (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING
                                                     StandardCopyOption/COPY_ATTRIBUTES])))
       FileVisitResult/CONTINUE))))

(defn- delete-tree!
  [^Path root]
  (when (Files/exists root (make-array java.nio.file.LinkOption 0))
    (Files/walkFileTree
     root
     (proxy [SimpleFileVisitor] []
       (visitFile [^Path file ^BasicFileAttributes _]
         (Files/deleteIfExists file)
         FileVisitResult/CONTINUE)
       (postVisitDirectory [^Path dir ^java.io.IOException _]
         (Files/deleteIfExists dir)
         FileVisitResult/CONTINUE)))))

(defn snapshot-storage!
  "Copia o diretório de storage para backup-dir (processo deve estar ocioso)."
  [storage-rel backup-rel]
  (let [from (path (persistence/resolve-storage-dir storage-rel))
        to (path (persistence/resolve-storage-dir backup-rel))]
    (when-not (Files/exists from (make-array java.nio.file.LinkOption 0))
      (throw (ex-info "backup_source_missing" {:storage storage-rel})))
    (delete-tree! to)
    (Files/createDirectories to (make-array java.nio.file.attribute.FileAttribute 0))
    (copy-tree! from to)
    {:from (str from) :to (str to) :ok true}))

(defn restore-storage!
  "Substitui storage-rel pelo conteúdo do backup."
  [storage-rel backup-rel]
  (let [from (path (persistence/resolve-storage-dir backup-rel))
        to (path (persistence/resolve-storage-dir storage-rel))]
    (when-not (Files/exists from (make-array java.nio.file.LinkOption 0))
      (throw (ex-info "backup_missing" {:backup backup-rel})))
    (delete-tree! to)
    (Files/createDirectories to (make-array java.nio.file.attribute.FileAttribute 0))
    (copy-tree! from to)
    {:restored-to (str to) :from (str from) :ok true}))
