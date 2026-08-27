- name: Build layers
        working-directory: lambda-layer
        run: |
          ./build-upstream-deps.sh
          ./build-downstream-deps.sh