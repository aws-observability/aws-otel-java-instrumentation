import argparse
import logging
import sys
import time

import boto3
import docker

import metric_data_params

logging.basicConfig(
    format="%(asctime)-8s %(levelname)-8s %(message)s",
    level=logging.INFO,
    datefmt="%FT%TZ",
)

logger = logging.getLogger(__file__)

# AWS Client API Constants

COMMON_ALARM_API_PARAMETERS = {
    "EvaluationPeriods": 4,
    "DatapointsToAlarm": 3,
    "ComparisonOperator": "GreaterThanOrEqualToThreshold",
    "TreatMissingData": "ignore",
}

CPU_LOAD_ALARM_NAME_PREFIX = "OTel Performance Test - CPU Load Percentage Spike"
TOTAL_MEMORY_ALARM_NAME_PREFIX = (
    "OTel Performance Test - Virtual Memory Usage Spike"
)

# Docker Client API Constants

APP_CONTAINER_NAME = "docker-performance-tests_app_1"
COLLECTOR_CONTAINER_NAME = "docker-performance-tests_otel_1"
LOAD_GENERATOR_CONTAINER_NAME = "docker-performance-tests_load-generator_1"


def parse_args():
    parser = argparse.ArgumentParser(
        description="""
        poll_during_performance_tests.py continuously polls the backend monitoring tool
        to see if an alarm has triggered because of a spike in the Performance Tests.
        """
    )

    metric_data_params.add_arguments(parser)

    parser.add_argument(
        "--cpu-load-threshold",
        required=True,
        type=int,
        help="""
        The threshold the CPU Load (as a percentage) must stay under to not
        trigger the alarm.

        Examples:

            --cpu-load-threshold=75
        """,
    )

    parser.add_argument(
        "--total-memory-threshold",
        required=True,
        type=int,
        help="""
        The threshold the Total Memory (in bytes) must stay under to not trigger
        the alarm.

        Examples:

            --total-memory-threshold=$(echo 1.5 \* 2^30 | bc)
        """,
    )

    parser.add_argument(
        "--matrix-commit-combo",
        required=True,
        help="""
        The matrix + commit combination which uniquely defines this Sample app
        by its platform used, its instrumentation type, and the commit SHA from
        which it was built. Used to create a unique name for the Performance
        Test Alarms.

        Examples:

            --matrix-commit-combo=flask-auto-12345abcdef38e38678a59da0911c9abcde12345
        """,
    )

    return parser.parse_args()


if __name__ == "__main__":
    logger.debug("Starting Alarm Polling Script.")

    docker_client = docker.from_env()

    try:
        if (
            docker_client.containers.get(APP_CONTAINER_NAME).attrs["State"][
                "Status"
            ]
            != "running"
        ):
            raise Exception("Failing because Sample App was not running.")

        if (
            docker_client.containers.get(COLLECTOR_CONTAINER_NAME).attrs[
                "State"
            ]["Status"]
            != "running"
        ):
            raise Exception("Failing because Collector was not running.")

        if (
            docker_client.containers.get(LOAD_GENERATOR_CONTAINER_NAME).attrs[
                "State"
            ]["Status"]
            != "running"
        ):
            raise Exception("Failing because Load Generator was not running.")

        args = parse_args()

        (
            cpu_load_metric_data_queries,
            total_memory_metric_data_queries,
        ) = metric_data_params.get_metric_data_params(args)

        aws_client = boto3.client("cloudwatch")

        unique_alarm_name_component = (
            f"{args.matrix_commit_combo}-{args.github_run_id}"
        )

        cpu_load_alarm_name = f"{CPU_LOAD_ALARM_NAME_PREFIX} ({unique_alarm_name_component}) Sample App"
        total_memory_alarm_name = f"{TOTAL_MEMORY_ALARM_NAME_PREFIX} ({unique_alarm_name_component}) Sample App"

        # Delete Alarms

        aws_client.delete_alarms(
            AlarmNames=[cpu_load_alarm_name, total_memory_alarm_name]
        )

        # Create Alarms

        aws_client.put_metric_alarm(
            **{
                **COMMON_ALARM_API_PARAMETERS,
                "AlarmName": cpu_load_alarm_name,
                "AlarmDescription": "Triggers when the CPU Load Percentage spikes above the allowed threshold DURING the ({unique_alarm_name_component}) Sample App Performance Test.",
                "Threshold": args.cpu_load_threshold,
                "Metrics": cpu_load_metric_data_queries,
            }
        )

        aws_client.put_metric_alarm(
            **{
                **COMMON_ALARM_API_PARAMETERS,
                "AlarmName": total_memory_alarm_name,
                "AlarmDescription": "Triggers when the Virtual Memory Usage spikes above the allowed threshold DURING the ({unique_alarm_name_component}) Sample App Performance Test.",
                "Threshold": args.total_memory_threshold,
                "Metrics": total_memory_metric_data_queries,
            }
        )

        # Poll Alarms

        did_tests_fail_during_execution = False
        time_of_last_alarm_poll = time.time()

        logger.info(
            "Begin polling alarms. Continue until Load Generator completes."
        )

        while (
            docker_client.containers.get(LOAD_GENERATOR_CONTAINER_NAME).attrs[
                "State"
            ]["Status"]
            == "running"
        ):
            if time.time() - time_of_last_alarm_poll > args.metrics_period:
                logger.info("Polling alarms now.")
                for alarm in aws_client.describe_alarms(
                    AlarmNames=[
                        cpu_load_alarm_name,
                        total_memory_alarm_name,
                    ]
                )["MetricAlarms"]:
                    alarm_desc = (
                        f"Alarm {alarm['AlarmName']} was {alarm['StateValue']} with reason: {alarm['StateReason']}.",
                    )
                    if alarm["StateValue"] == "ALARM":
                        logger.error(alarm_desc)
                        did_tests_fail_during_execution = True
                    else:
                        logger.info(alarm_desc)
                time_of_last_alarm_poll = time.time()

            time.sleep(3)

        logger.info("Done polling Performance Test alarms.")

        # Delete Alarms

        aws_client.delete_alarms(
            AlarmNames=[cpu_load_alarm_name, total_memory_alarm_name]
        )

        # End the Polling

        if did_tests_fail_during_execution:
            logger.error(
                "Failing because of alarms triggered during Performance Test."
            )
            sys.exit(2)

    finally:
        for container_name in [
            APP_CONTAINER_NAME,
            LOAD_GENERATOR_CONTAINER_NAME,
            COLLECTOR_CONTAINER_NAME,
        ]:
            docker_client.containers.get(container_name).stop()
