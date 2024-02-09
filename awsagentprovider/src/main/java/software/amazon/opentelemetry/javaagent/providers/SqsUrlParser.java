package software.amazon.opentelemetry.javaagent.providers;

public class SqsUrlParser {
    private static final char ARN_DELIMETER = ':';
    private static final String HTTP_SCHEMA = "http://";
    private static final String HTTPS_SCHEMA = "https://";

    public static String getSqsRemoteTarget(String sqsUrl) {
        sqsUrl = stripSchemaFromUrl(sqsUrl);

        if (!isSqsUrl(sqsUrl) && !isLegacySqsUrl(sqsUrl) && !isCustomUrl(sqsUrl)) {
            return null;
        }

        String region = getRegion(sqsUrl);
        String accountId = getAccountId(sqsUrl);
        String partition = getPartition(sqsUrl);
        String queueName = getQueueName(sqsUrl);

        StringBuilder remoteTarget = new StringBuilder();

        if (region == null && accountId == null && partition == null && queueName == null) {
            return null;
        }

        if (region != null && accountId != null && partition != null && queueName != null) {
            remoteTarget.append("arn");
        }

        remoteTarget
                .append(ARN_DELIMETER)
                .append(nullToEmpty(partition))
                .append(ARN_DELIMETER)
                .append("sqs")
                .append(ARN_DELIMETER)
                .append(nullToEmpty(region))
                .append(ARN_DELIMETER)
                .append(nullToEmpty(accountId))
                .append(ARN_DELIMETER)
                .append(queueName);

        return remoteTarget.toString();
    }

    private static String stripSchemaFromUrl(String url) {
        return url
                .replace(HTTP_SCHEMA, "")
                .replace(HTTPS_SCHEMA, "");
    }

    private static String getRegion(String sqsUrl) {
        if (sqsUrl == null) {
            return null;
        }

        if (sqsUrl.startsWith("queue.amazonaws.com/")) {
            return "us-east-1";
        } else if (isSqsUrl(sqsUrl)) {
            return getRegionFromSqsUrl(sqsUrl);
        } else if (isLegacySqsUrl(sqsUrl)) {
            return getRegionFromLegacySqsUrl(sqsUrl);
        } else {
            return null;
        }
    }

    private static boolean isSqsUrl(String sqsUrl) {
        String[] split = sqsUrl.split("/");

        return
                split.length == 3 &&
                split[0].startsWith("sqs.") &&
                split[0].endsWith(".amazonaws.com") &&
                isAccountId(split[1]) &&
                isValidQueueName(split[2]);
    }

    private static boolean isLegacySqsUrl(String sqsUrl) {
        String[] split = sqsUrl.split("/");

        return split.length == 3 &&
                split[0].endsWith(".queue.amazonaws.com") &&
                isAccountId(split[1]) &&
                isValidQueueName(split[2]);
    }

    private static boolean isCustomUrl(String sqsUrl) {
        String[] split = sqsUrl.split("/");
        return
                split.length == 3 &&
                isAccountId(split[1]) &&
                isValidQueueName(split[2]);
    }

    private static boolean isValidQueueName(String input) {
        if (input.length() == 0 || input.length() > 80) {
            return false;
        }

        for (Character c: input.toCharArray()) {
            if (c != '_' &&
                c != '-' &&
                !Character.isAlphabetic(c) &&
                !Character.isDigit(c))
            {
                return false;
            }
        }

        return true;
    }

    private static boolean isAccountId(String input) {
        if (input.length() != 12) {
            return false;
        }

        try {
            Long.valueOf(input);
        } catch (Exception e) {
            return false;
        }

        return true;
    }

    private static String getRegionFromSqsUrl(String sqsUrl) {
        String[] split = sqsUrl.split("\\.");

        if (split.length >= 2) {
            return split[1];
        }

        return null;
    }

    private static String getRegionFromLegacySqsUrl(String sqsUrl) {
        String[] split = sqsUrl.split("\\.");
        return split[0];
    }

    private static String getAccountId(String sqsUrl) {
        if (sqsUrl == null) {
            return null;
        }

        String[] split = sqsUrl.split("/");
        if (split.length >= 2) {
            return split[1];
        }

        return null;
    }

    private static String getPartition(String sqsUrl) {
        String region = getRegion(sqsUrl);

        if (region == null) {
            return null;
        }

        if (region.startsWith("us-gov-")) {
            return "aws-us-gov";
        } else if (region.startsWith("cn-")) {
            return "aws-cn";
        } else {
            return "aws";
        }
    }

    private static String getQueueName(String sqsUrl) {
        String[] split = sqsUrl.split("/");

        if (split.length >= 3) {
            return split[2];
        }

        return null;
    }

    private static String nullToEmpty(String input) {
        return input == null ? "" : input;
    }
}
