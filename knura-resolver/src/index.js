// AWS Lambda entry point, behind an API Gateway HTTP API on tap.knura.io. Set the TABLE_NAME
// environment variable to the DynamoDB table holding owner records.

import { createHandler } from "./resolver.js";
import { DynamoStore } from "./dynamo-store.js";

const tableName = process.env.TABLE_NAME;
if (!tableName) throw new Error("set the TABLE_NAME environment variable to the DynamoDB table name");

export const handler = createHandler({ store: new DynamoStore(tableName) });
