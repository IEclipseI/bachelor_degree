{
  "sensitive-variables": [
    "token"
  ],
  "variables": {
    "token": "{{env `TOKEN`}}"
  },
  "builders": [
    {
      "type": "yandex",
      "token": "{{user `token`}}",
      "folder_id": "b1gn9kumdun5joqg74dl",
      "source_image_family": "ubuntu-1804-lts",
      "ssh_username": "ubuntu",
      "use_ipv4_nat": "true",
      "image_name": "ubuntu-maven-jdk11-{{timestamp}}",
      "image_description": "Образ для тестирования бакалаврской работы",
      "state_timeout": "15m"
    }
  ],

  "provisioners": [
    {
      "type": "shell",
      "inline": [
        "sleep 120",
        "sudo apt-get -y update",
        "sudo apt-get -y install openjdk-11-jdk",
        "sudo apt-get -y install maven"
      ]
    }
  ]
}