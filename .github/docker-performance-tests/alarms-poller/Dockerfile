FROM python:3.9

COPY . .

RUN pip install docker boto3

CMD python ./poll_during_performance_tests.py --logs-namespace $LOGS_NAMESPACE \
                                              --metrics-period $HOSTMETRICS_INTERVAL_SECS \
                                              --num-of-cpus $NUM_OF_CPUS \
                                              --app-process-command-line-dimension-value "$APP_PROCESS_COMMAND_LINE_DIMENSION_VALUE" \
                                              --target-sha $TARGET_SHA \
                                              --github-run-id $GITHUB_RUN_ID \
                                              --cpu-load-threshold $CPU_LOAD_THRESHOLD \
                                              --total-memory-threshold $TOTAL_MEMORY_THRESHOLD \
                                              --matrix-commit-combo $MATRIX_COMMIT_COMBO
