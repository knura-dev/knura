import assert from "node:assert/strict";
import { test } from "node:test";
import { ConditionalCheckFailedException } from "@aws-sdk/client-dynamodb";
import { DynamoStore } from "../src/dynamo-store.js";

const RECORD = {
    owner: "rq5tw8np3a",
    server: "https://knurd.example.ts.net",
    seq: 1759881600000,
    publicKey: "d7".repeat(32),
    signature: "f8".repeat(64),
    updatedAt: "2026-10-08T00:00:00.000Z",
};

function fakeClient(answer) {
    const sent = [];
    return {
        sent,
        send: async command => {
            sent.push(command);
            return answer(command);
        },
    };
}

test("put writes the record only for a new owner or the same key with a higher seq", async () => {
    const client = fakeClient(() => ({}));
    assert.equal(await new DynamoStore("owners", client).put(RECORD), true);
    const input = client.sent[0].input;
    assert.equal(input.TableName, "owners");
    assert.deepEqual(input.Item.seq, { N: "1759881600000" });
    assert.equal(input.ConditionExpression, "attribute_not_exists(#owner) OR (publicKey = :pk AND seq < :seq)");
    assert.deepEqual(input.ExpressionAttributeValues, {
        ":pk": { S: RECORD.publicKey },
        ":seq": { N: "1759881600000" },
    });
});

test("a failed condition is reported as false", async () => {
    const client = fakeClient(() => {
        throw new ConditionalCheckFailedException({ message: "The conditional request failed", $metadata: {} });
    });
    assert.equal(await new DynamoStore("owners", client).put(RECORD), false);
});

test("other errors are thrown", async () => {
    const client = fakeClient(() => {
        throw new Error("throttled");
    });
    await assert.rejects(new DynamoStore("owners", client).put(RECORD), /throttled/);
});

test("get reads the record back with a consistent read", async () => {
    const client = fakeClient(() => ({
        Item: {
            owner: { S: RECORD.owner },
            server: { S: RECORD.server },
            seq: { N: String(RECORD.seq) },
            publicKey: { S: RECORD.publicKey },
            signature: { S: RECORD.signature },
            updatedAt: { S: RECORD.updatedAt },
        },
    }));
    const store = new DynamoStore("owners", client);
    assert.deepEqual(await store.get(RECORD.owner), RECORD);
    assert.equal(client.sent[0].input.ConsistentRead, true);

    const empty = new DynamoStore("owners", fakeClient(() => ({})));
    assert.equal(await empty.get(RECORD.owner), null);
});
