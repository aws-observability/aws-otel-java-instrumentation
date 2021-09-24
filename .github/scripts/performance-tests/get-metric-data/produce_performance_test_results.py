import argparse
import json
import logging
from datetime import datetime, timedelta
from statistics import mean

import boto3

import metric_data_params

logging.basicConfig(
    format="%(asctime)-8s %(levelname)-8s %(message)s",
    level=logging.INFO,
    datefmt="%FT%TZ",
)

logger = logging.getLogger(__file__)


def parse_args():
    parser = argparse.ArgumentParser(
        description="""
        produce_performance_test_results.py produces overall results for the
        performance tests that were just run as JSON output.
        """
    )

    metric_data_params.add_arguments(parser)

    parser.add_argument(
        "--test-duration-minutes",
        required=True,
        type=int,
        help="""
        The duration of the performance test. Used as the starting point for
        determining which metrics to include in the performance test results.

        Examples:

            --test-duration-minutes=$(echo 1.5 \* 2^30 | bc)
        """,
    )

    return parser.parse_args()


if __name__ == "__main__":
    logger.debug("Starting script to get performance test results.")

    args = parse_args()

    start_time = (
        datetime.utcnow() - timedelta(minutes=args.test_duration_minutes)
    ).strftime("%FT%TZ")

    (
        cpu_load_metric_data_queries,
        total_memory_metric_data_queries,
    ) = metric_data_params.get_metric_data_params(args)

    aws_client = boto3.client("cloudwatch")

    metric_data_results = aws_client.get_metric_data(
        StartTime=start_time,
        EndTime=datetime.utcnow(),
        MetricDataQueries=cpu_load_metric_data_queries
        + total_memory_metric_data_queries,
    )["MetricDataResults"]

    benchmarks_json = json.dumps(
        {
            "benchmarks": [
                {
                    "Name": "Soak Test Average CPU Load",
                    "Value": mean(
                        next(
                            metric_data
                            for metric_data in metric_data_results
                            if metric_data["Id"] == "cpu_load_expr"
                        )["Values"]
                    ),
                    "Unit": "Percent",
                },
                {
                    "Name": "Soak Test Average Virtual Memory Used",
                    "Value": mean(
                        next(
                            metric_data
                            for metric_data in metric_data_results
                            if metric_data["Id"] == "total_memory_expr"
                        )["Values"]
                    )
                    / (2 ** 20),
                    "Unit": "Megabytes",
                },
            ]
        },
        indent=4,
    )

    logger.info("Found these benchmarks: %s", benchmarks_json)

    with open("output.json", "w") as file_context:
        file_context.write(benchmarks_json)

    logger.info("Done producing Performance Test results.")
