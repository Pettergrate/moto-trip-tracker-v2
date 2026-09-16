package com.mototriptracker.app.core.common

/**
 * F0.7 §2.3 / F0.8 §8.1: stable, locally-generated identity for entities that
 * must survive export/import — never a rowid, name, or UI position. Behind a
 * seam so tests can supply deterministic IDs instead of random UUIDs.
 */
interface IdGenerator {
    fun newId(): String
}
