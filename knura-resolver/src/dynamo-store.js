// Owner records in a DynamoDB table with partition key "owner" (a string). The AWS SDK comes with
// the Lambda Node.js runtime, so this package has no dependencies to install.

import {
    ConditionalCheckFailedException,
    DynamoDBClient,
    GetItemCommand,
    PutItemCommand,
} from "@aws-sdk/client-dynamodb";

export class DynamoStore {
    constructor(tableName, client = new DynamoDBClient({})) {
        this.tableName = tableName;
        this.client = client;
    }

    async get(owner) {
        const { Item } = await this.client.send(
            new GetItemCommand({ TableName: this.tableName, Key: { owner: { S: owner } }, ConsistentRead: true }),
        );
        if (!Item) return null;
        return {
            owner: Item.owner.S,
            server: Item.server.S,
            seq: Number(Item.seq.N),
            publicKey: Item.publicKey.S,
            signature: Item.signature.S,
            updatedAt: Item.updatedAt.S,
        };
    }

    /** Same rule as MemoryStore.put, enforced by DynamoDB so two racing updates can't both win. */
    async put(record) {
        try {
            await this.client.send(
                new PutItemCommand({
                    TableName: this.tableName,
                    Item: {
                        owner: { S: record.owner },
                        server: { S: record.server },
                        seq: { N: String(record.seq) },
                        publicKey: { S: record.publicKey },
                        signature: { S: record.signature },
                        updatedAt: { S: record.updatedAt },
                    },
                    ConditionExpression: "attribute_not_exists(#owner) OR (publicKey = :pk AND seq < :seq)",
                    ExpressionAttributeNames: { "#owner": "owner" },
                    ExpressionAttributeValues: { ":pk": { S: record.publicKey }, ":seq": { N: String(record.seq) } },
                }),
            );
            return true;
        } catch (e) {
            if (e instanceof ConditionalCheckFailedException) return false;
            throw e;
        }
    }
}
