<#
.SYNOPSIS
    Obtem o refresh token do Gmail para a integracao do SolarSync (secao 9 do CLAUDE.md).

.DESCRIPTION
    Faz o consentimento OAuth uma unica vez e imprime o refresh_token, que e o valor de
    SOLARSYNC_GMAIL_REFRESH_TOKEN. O refresh token do Google nao expira por tempo: o que o
    invalida e revogar o acesso ou trocar a senha da conta. Por isso este script se roda uma vez
    e nao vira parte de nenhum fluxo automatico.

    O escopo pedido e apenas gmail.readonly. O job nunca escreve na caixa -- e por isso que a
    marca de "ja processei" vive na tabela email_coelba, e nao num rotulo no Gmail.

    O redirecionamento e para http://localhost:<porta>, o loopback que os clientes OAuth do tipo
    "Aplicativo para computador" (Desktop app) aceitam sem precisar registrar a URL. Com um
    cliente do tipo "Aplicativo da Web" isto falha com redirect_uri_mismatch: nesse caso,
    cadastre a URL exata que o script imprime nas "URIs de redirecionamento autorizados".

.PARAMETER ClientId
    Id do cliente OAuth criado no Google Cloud Console.

.PARAMETER ClientSecret
    Segredo do mesmo cliente OAuth.

.PARAMETER Porta
    Porta local que recebe o retorno do consentimento. So precisa mudar se 8765 estiver ocupada.

.EXAMPLE
    .\scripts\obter-refresh-token-gmail.ps1 -ClientId "123-abc.apps.googleusercontent.com" -ClientSecret "GOCSPX-..."

.NOTES
    IMPORTANTE: faca o login, no navegador, com a caixa que RECEBE o e-mail da Coelba. E a caixa
    autorizada aqui que o job vai ler -- nao adianta autorizar uma conta e configurar outra.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $ClientId,

    [Parameter(Mandatory = $true)]
    [string] $ClientSecret,

    [int] $Porta = 8765
)

$ErrorActionPreference = 'Stop'

$redirecionamento = "http://localhost:$Porta/"
$escopo = 'https://www.googleapis.com/auth/gmail.readonly'

# access_type=offline e o que faz o Google devolver um refresh token; prompt=consent forca a
# tela de consentimento mesmo se a conta ja autorizou antes -- sem ele, uma segunda execucao
# devolve so o access token e o refresh_token vem vazio, que e a pegadinha classica aqui.
$parametros = @(
    "client_id=$([uri]::EscapeDataString($ClientId))"
    "redirect_uri=$([uri]::EscapeDataString($redirecionamento))"
    "response_type=code"
    "scope=$([uri]::EscapeDataString($escopo))"
    "access_type=offline"
    "prompt=consent"
) -join '&'

$urlConsentimento = "https://accounts.google.com/o/oauth2/v2/auth?$parametros"

$ouvinte = New-Object System.Net.HttpListener
$ouvinte.Prefixes.Add($redirecionamento)

try {
    $ouvinte.Start()
}
catch {
    Write-Error ("Nao foi possivel escutar em $redirecionamento. Porta ocupada? " +
        "Tente outra com -Porta. Detalhe: $($_.Exception.Message)")
    exit 1
}

Write-Host ''
Write-Host 'Abrindo o navegador para o consentimento do Google.' -ForegroundColor Cyan
Write-Host "Entre com a caixa que RECEBE o e-mail da Coelba." -ForegroundColor Yellow
Write-Host ''
Write-Host 'Se o navegador nao abrir, cole esta URL nele:'
Write-Host $urlConsentimento -ForegroundColor DarkGray
Write-Host ''
Write-Host "Aguardando o retorno em $redirecionamento ..."

Start-Process $urlConsentimento | Out-Null

$contexto = $ouvinte.GetContext()
$codigo = $contexto.Request.QueryString['code']
$erro = $contexto.Request.QueryString['error']

$html = if ($codigo) {
    '<html><body style="font-family:sans-serif;padding:40px"><h2>Autorizado.</h2>' +
    '<p>Pode fechar esta aba e voltar ao terminal.</p></body></html>'
}
else {
    '<html><body style="font-family:sans-serif;padding:40px"><h2>Autorizacao negada.</h2>' +
    '<p>Volte ao terminal.</p></body></html>'
}

$bytes = [System.Text.Encoding]::UTF8.GetBytes($html)
$contexto.Response.ContentType = 'text/html; charset=utf-8'
$contexto.Response.ContentLength64 = $bytes.Length
$contexto.Response.OutputStream.Write($bytes, 0, $bytes.Length)
$contexto.Response.Close()
$ouvinte.Stop()

if (-not $codigo) {
    Write-Error "O Google nao devolveu um codigo de autorizacao. Erro: $erro"
    exit 1
}

Write-Host 'Codigo recebido. Trocando por tokens...' -ForegroundColor Cyan

$resposta = Invoke-RestMethod -Method Post -Uri 'https://oauth2.googleapis.com/token' -Body @{
    code          = $codigo
    client_id     = $ClientId
    client_secret = $ClientSecret
    redirect_uri  = $redirecionamento
    grant_type    = 'authorization_code'
}

if (-not $resposta.refresh_token) {
    Write-Error ("O Google devolveu um access token mas nenhum refresh token. Isso acontece " +
        "quando a conta ja autorizou este cliente antes. Revogue o acesso em " +
        "https://myaccount.google.com/permissions e rode de novo.")
    exit 1
}

Write-Host ''
Write-Host '--- refresh token obtido ---' -ForegroundColor Green
Write-Host ''
Write-Host $resposta.refresh_token
Write-Host ''
Write-Host 'Guarde-o em uma das duas formas (nunca no repositorio):' -ForegroundColor Cyan
Write-Host '  dev  : config/application.properties -> solarsync.gmail.refresh-token=...'
Write-Host '  prod : variavel de ambiente SOLARSYNC_GMAIL_REFRESH_TOKEN'
Write-Host ''
Write-Host ("Escopos concedidos: " + $resposta.scope)
