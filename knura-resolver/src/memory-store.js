// Owner records kept in memory: for tests and the local dev server.

export class MemoryStore {
    constructor() {
        this.records = new Map();
    }

    async get(owner) {
        const record = this.records.get(owner);
        return record ? { ...record } : null;
    }

    /**
     * Saves a record only if the owner is new, or the stored record has the same public key and a
     * lower seq. Returns false if that condition failed, so racing updates can't both win.
     */
    async put(record) {
        const old = this.records.get(record.owner);
        if (old && (old.publicKey !== record.publicKey || old.seq >= record.seq)) return false;
        this.records.set(record.owner, { ...record });
        return true;
    }
}
